package com.shareCart.project.domain.room.scheduler;

import com.shareCart.project.domain.room.model.mapper.ParticipantItemMapper;
import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private static final String DIRTY_SET_KEY = "dirty:items";
    private static final int POP_BATCH_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final ParticipantItemMapper participantItemMapper;

    @Scheduled(fixedDelay = 30000)
    public void syncToDb() {
        try {
            List<String> batch = redisTemplate.opsForSet().pop(DIRTY_SET_KEY, POP_BATCH_SIZE);
            if (batch == null || batch.isEmpty()) {
                return;
            }

            List<String> failedItemIds = new ArrayList<>();
            for (String itemId : batch) {
                if (!syncItem(itemId)) {
                    failedItemIds.add(itemId);
                }
            }

            if (!failedItemIds.isEmpty()) {
                redisTemplate.opsForSet().add(DIRTY_SET_KEY, failedItemIds.toArray(new String[0]));
            }
        } catch (Exception e) {
            log.error("Redis-DB 동기화 배치 실행 중 예외 발생", e);
        }
    }

    @Scheduled(fixedDelay = 60 * 60 * 1000) //
    public void reconcileDirtySet() {
        int count = 0;
        try {
            ScanOptions options = ScanOptions.scanOptions().match("zset:*").count(100).build();
            try (Cursor<byte[]> cursor = redisTemplate.executeWithStickyConnection(
                    (RedisCallback<Cursor<byte[]>>) (RedisConnection conn) -> conn.scan(options))) {
                while (cursor.hasNext()) {
                    String key = new String(cursor.next(), StandardCharsets.UTF_8);
                    String itemId = key.substring("zset:".length());
                    try {
                        Long.parseLong(itemId);
                        redisTemplate.opsForSet().add(DIRTY_SET_KEY, itemId);
                        count++;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            log.info("dirty set 안전망 스캔 완료: {}개 itemId 재마킹", count);
        } catch (Exception e) {
            log.error("dirty set 안전망 스캔 실패", e);
        }
    }

    private boolean syncItem(String itemId) {
        Long parsedItemId;
        try {
            parsedItemId = Long.parseLong(itemId);
        } catch (NumberFormatException e) {
            log.warn("잘못된 dirty set itemId 형식: {}", itemId);
            return true;
        }

        String zsetKey = "zset:" + itemId;
        Set<ZSetOperations.TypedTuple<String>> entries =
                redisTemplate.opsForZSet().rangeWithScores(zsetKey, 0, -1);

        if (entries == null || entries.isEmpty()) {
            return true;
        }

        List<ParticipantItemVO> batch = new ArrayList<>(entries.size());
        for (ZSetOperations.TypedTuple<String> entry : entries) {
            try {
                if (entry.getValue() == null) continue;
                Long participantId = Long.parseLong(entry.getValue());
                int qty = entry.getScore() == null ? 0 : entry.getScore().intValue();
                batch.add(ParticipantItemVO.builder()
                        .participantId(participantId)
                        .itemId(parsedItemId)
                        .allocQuantity(qty)
                        .build());
            } catch (NumberFormatException e) {
                log.warn("잘못된 zset 엔트리 형식: itemId={}, entry={}", itemId, entry, e);
            }
        }

        if (batch.isEmpty()) {
            return true;
        }

        try {
            participantItemMapper.upsertAllocationBatch(batch);
            return true;
        } catch (Exception e) {
            log.error("DB Batch Upsert 동기화 실패: itemId={}", itemId, e);
            return false;
        }
    }
}