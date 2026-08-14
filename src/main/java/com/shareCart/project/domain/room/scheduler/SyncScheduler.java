package com.shareCart.project.domain.room.scheduler;

import com.shareCart.project.domain.room.model.mapper.ParticipantItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final StringRedisTemplate redisTemplate;
    private final ParticipantItemMapper participantItemMapper;

    @Scheduled(fixedDelay = 5000)
    public void syncToDb() {
        try {
            Set<String> zsetKeys = redisTemplate.keys("zset:*");
            if (zsetKeys == null || zsetKeys.isEmpty()) {
                return;
            }

            for (String zsetKey : zsetKeys) {
                syncItem(zsetKey);
            }
        } catch (Exception e) {
            log.error("Redis-DB 동기화 배치 실패", e);
        }
    }

    private void syncItem(String zsetKey) {
        Long itemId = extractItemId(zsetKey);
        if (itemId == null) return;

        Set<ZSetOperations.TypedTuple<String>> entries =
                redisTemplate.opsForZSet().rangeWithScores(zsetKey, 0, -1);

        if (entries == null || entries.isEmpty()) return;

        for (ZSetOperations.TypedTuple<String> entry : entries) {
            try {
                Long participantId = Long.parseLong(entry.getValue());
                int qty = entry.getScore().intValue();
                participantItemMapper.upsertAllocation(participantId, itemId, qty);
            } catch (Exception e) {
                log.error("동기화 실패: itemId={}, entry={}", itemId, entry, e);
            }
        }
    }

    private Long extractItemId(String zsetKey) {
        try {
            return Long.parseLong(zsetKey.replace("zset:", ""));
        } catch (NumberFormatException e) {
            log.warn("잘못된 zset 키 형식: {}", zsetKey);
            return null;
        }
    }
}