package com.shareCart.project.domain.room.scheduler;

import com.shareCart.project.domain.room.model.mapper.ParticipantItemMapper;
import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {
    private static final String DIRTY_SET_KEY = "dirty:items";
    private static final String PROCESSING_ZSET_KEY = "processing:items";
    private static final int BATCH_SIZE = 100;
    private static final long PROCESSING_TIMEOUT_MS = 30_000L;
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = script("scripts/claimDirtyItems.lua", List.class);
    private static final DefaultRedisScript<Long> RECOVER_SCRIPT = script("scripts/recoverProcessingItems.lua", Long.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT = script("scripts/ackProcessingItem.lua", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ParticipantItemMapper participantItemMapper;

    @Scheduled(fixedDelay = 30_000)
    public void syncToDb() {
        try {
            long claimedAt = System.currentTimeMillis();
            List<?> claimed = redisTemplate.execute(CLAIM_SCRIPT,
                    List.of(DIRTY_SET_KEY, PROCESSING_ZSET_KEY),
                    String.valueOf(BATCH_SIZE), String.valueOf(claimedAt));
            if (claimed == null) return;
            for (Object member : claimed) {
                String itemId = String.valueOf(member);
                try {
                    if (syncItem(itemId)) {
                        redisTemplate.execute(ACK_SCRIPT, List.of(PROCESSING_ZSET_KEY),
                                itemId, String.valueOf(claimedAt));
                    }
                } catch (Exception e) {
                    log.error("Item synchronization failed; retained for recovery: itemId={}", itemId, e);
                }
            }
        } catch (Exception e) {
            log.error("Redis-DB synchronization batch failed", e);
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void recoverTimedOutItems() {
        long cutoff = System.currentTimeMillis() - PROCESSING_TIMEOUT_MS;
        try {
            Set<String> expired = redisTemplate.opsForZSet()
                    .rangeByScore(PROCESSING_ZSET_KEY, Double.NEGATIVE_INFINITY, cutoff, 0, BATCH_SIZE);
            if (expired == null) return;
            for (String itemId : expired) {
                redisTemplate.execute(RECOVER_SCRIPT, List.of(PROCESSING_ZSET_KEY, DIRTY_SET_KEY),
                        itemId, String.valueOf(cutoff));
            }
        } catch (Exception e) {
            log.error("Processing queue recovery failed", e);
        }
    }

    private boolean syncItem(String itemId) {
        long parsedItemId;
        try {
            parsedItemId = Long.parseLong(itemId);
        } catch (NumberFormatException e) {
            log.warn("Skipping invalid dirty set itemId: {}", itemId);
            return true;
        }
        Set<ZSetOperations.TypedTuple<String>> entries =
                redisTemplate.opsForZSet().rangeWithScores("zset:" + itemId, 0, -1);
        if (entries == null || entries.isEmpty()) return true;

        List<ParticipantItemVO> batch = new ArrayList<>(entries.size());
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            try {
                if (entry.getValue() == null) continue;
                long participantId = Long.parseLong(entry.getValue());
                int qty = entry.getScore() == null ? 0 : entry.getScore().intValue();
                batch.add(ParticipantItemVO.builder()
                        .participantId(participantId).itemId(parsedItemId).allocQuantity(qty).build());
            } catch (NumberFormatException e) {
                log.warn("Skipping invalid allocation entry: itemId={}, entry={}", itemId, entry, e);
            }
        }
        if (batch.isEmpty()) return true;
        try {
            participantItemMapper.upsertAllocationBatch(batch);
            return true;
        } catch (Exception e) {
            log.error("DB batch upsert failed: itemId={}", itemId, e);
            return false;
        }
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }
}
