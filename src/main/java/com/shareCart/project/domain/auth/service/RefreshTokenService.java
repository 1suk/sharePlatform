package com.shareCart.project.domain.auth.service;

import com.shareCart.project.global.security.TokenVersionKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String REFRESH_TOKEN_PREFIX = "RT:";

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.refresh-expiration}")
    private long refreshTokenExpiration;

    public void save(String email, String refreshToken) {
        redisTemplate.opsForValue().set(
            REFRESH_TOKEN_PREFIX + email,
            refreshToken,
            refreshTokenExpiration,
                TimeUnit.MICROSECONDS
        );
    }

    public boolean validate(String email, String refreshToken) {
        String stored = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + email);
        return stored != null && stored.equals(refreshToken);
    }

    public void delete(String email) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + email);
    }

    public long getTokenVersion(String email) {
        String version = redisTemplate.opsForValue().get(TokenVersionKeys.key(email));
        return version == null ? 0L : Long.parseLong(version);
    }

    public void increaseTokenVersion(String email) {
        redisTemplate.opsForValue().increment(TokenVersionKeys.key(email));
        redisTemplate.expire(TokenVersionKeys.key(email), refreshTokenExpiration, TimeUnit.MILLISECONDS);
    }
}
