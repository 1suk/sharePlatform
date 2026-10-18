package com.shareCart.project.domain.room.scheduler;

import com.shareCart.project.domain.room.model.mapper.ParticipantItemMapper;
import com.shareCart.project.domain.room.model.vo.ParticipantItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AllocationFlusher {
    private final StringRedisTemplate redisTemplate;
    private final ParticipantItemMapper participantItemMapper;

    /** Redis zset:{itemId}의 할당을 DB에 upsert. false면 DB 반영 실패(재시도 필요). */
    public boolean flush(String itemId) {
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
}
