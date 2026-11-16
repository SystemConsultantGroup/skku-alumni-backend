package com.scg.alumni.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Fixed window counter; Redis failure denies login instead of removing the limit. */
@Service
@RequiredArgsConstructor
public class LoginAttemptLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], 900) end
            return n
            """, Long.class);
    private final StringRedisTemplate redis;

    public void check(AuthScope scope, String identifier) {
        checkKey(scope, "identifier", identifier.trim().toLowerCase(Locale.ROOT));
    }

    public void checkAccount(AuthScope scope, long accountId) {
        checkKey(scope, "account", Long.toString(accountId));
    }

    private void checkKey(AuthScope scope, String category, String value) {
        String key = "auth:login-attempt:" + scope.name().toLowerCase(Locale.ROOT) + ":" + category + ":" + digest(value);
        Long attempts;
        try {
            attempts = redis.execute(INCREMENT, List.of(key));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Login temporarily unavailable", exception);
        }
        if (attempts == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Login temporarily unavailable");
        if (attempts > 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
