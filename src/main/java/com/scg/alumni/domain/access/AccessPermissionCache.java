package com.scg.alumni.domain.access;

import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;
import java.time.Duration;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Date-scoped permission cache. */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccessPermissionCache {
    private static final Duration TTL = Duration.ofSeconds(30);
    private final StringRedisTemplate redis;
    private final AccessQualificationService qualifications;
    private final OfficerTermPolicyProperties policy;

    public AccessLevel currentLevel(long userId) {
        LocalDate date = LocalDate.now(policy.zone());
        String key = null;
        try {
            String generation = redis.opsForValue().get(generationKey(userId));
            key = "auth:access:v3:" + userId + ":" + (generation == null ? "0" : generation) + ":" + date;
            String cached = redis.opsForValue().get(key);
            if (cached != null) return AccessLevel.valueOf(cached);
        } catch (RuntimeException unavailable) {
            log.warn("Permission cache read failed for user {}; computing from database", userId, unavailable);
            key = null;
        }
        AccessLevel authoritative = qualifications.levelAt(userId, date);
        if (key != null) {
            try { redis.opsForValue().set(key, authoritative.name(), TTL); }
            catch (RuntimeException unavailable) { log.warn("Permission cache write failed for user {}", userId, unavailable); }
        }
        return authoritative;
    }

    /** Changes the namespace after a successful mutation; existing entries expire naturally. */
    public void invalidate(long userId) {
        try {
            redis.opsForValue().increment(generationKey(userId));
        } catch (RuntimeException firstFailure) {
            log.warn("Permission cache invalidation failed for user {}; retrying once", userId, firstFailure);
            redis.opsForValue().increment(generationKey(userId));
        }
    }

    private String generationKey(long userId) { return "auth:access:user-generation:" + userId; }
}
