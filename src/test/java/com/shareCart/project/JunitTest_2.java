package com.shareCart.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.shareCart.project.domain.room.service.RoomParticipantService;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
class JunitTest_2 {

    @Autowired
    private RoomParticipantService roomParticipantService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Long TEST_ROOM_ID = 7L;
    private static final Long TEST_ITEM_ID = 13L;

    @BeforeEach
    void setUp() {
        redisTemplate.delete("zset:" + TEST_ITEM_ID);
        redisTemplate.delete("total:" + TEST_ITEM_ID);
    }

    @Test
    void concurrentRequestsShouldNotExceedCap() throws InterruptedException {
        int threadCount = 51;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (long i = 10; i <= 60; i++) {
            final String email = "user" + i + "@test.com";
            executor.submit(() -> {
                try {
                    roomParticipantService.allocateItem(
                            TEST_ROOM_ID, email, TEST_ITEM_ID,
                            UUID.randomUUID().toString(), 1
                    );
                    successCount.incrementAndGet();
                } catch (IllegalStateException ignored) {
                    // 한도 초과로 인한 정상 실패
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        Long total = Long.parseLong(redisTemplate.opsForValue().get("total:" + TEST_ITEM_ID));
        assertThat(total).isLessThanOrEqualTo(30L);
        System.out.println("성공한 요청 수: " + successCount.get());
        System.out.println("최종 total: " + total);
    }
}