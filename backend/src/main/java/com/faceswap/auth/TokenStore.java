package com.faceswap.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed token state: refresh tokens are allow-listed by jti (so they can be rotated and revoked),
 * access tokens are deny-listed on logout until they expire.
 */
@Component
public class TokenStore {

    private static final String REFRESH = "auth:refresh:";
    private static final String BLACKLIST = "auth:blacklist:";

    private final StringRedisTemplate redis;

    public TokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void saveRefresh(String jti, long userId, Instant expiresAt) {
        redis.opsForValue().set(REFRESH + jti, String.valueOf(userId), ttlUntil(expiresAt));
    }

    /** Returns true only for the first caller; a second use of the same refresh token is rejected. */
    public boolean consumeRefresh(String jti) {
        return Boolean.TRUE.equals(redis.delete(REFRESH + jti));
    }

    public void blacklistAccess(String jti, Instant expiresAt) {
        Duration ttl = ttlUntil(expiresAt);
        if (!ttl.isZero()) {
            redis.opsForValue().set(BLACKLIST + jti, "1", ttl);
        }
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST + jti));
    }

    private static Duration ttlUntil(Instant expiresAt) {
        Duration d = Duration.between(Instant.now(), expiresAt);
        return d.isNegative() ? Duration.ZERO : d.plusSeconds(1);
    }
}
