package com.scg.alumni.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final AuthProperties authProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public String issue(AuthenticatedPrincipal principal) {
        String token = randomToken();
        String key = key(principal.scope(), token);
        try {
            String value = objectMapper.writeValueAsString(new StoredRefreshPrincipal(principal.id(), principal.scope(), principal.name()));
            stringRedisTemplate.opsForValue().set(key, value, authProperties.getRefreshTokenTtl());
            return token;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Refresh token을 저장하지 못했습니다.", exception);
        }
    }

    public Optional<AuthenticatedPrincipal> consume(AuthScope scope, String token) {
        String key = key(scope, token);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return Optional.empty();
        }
        stringRedisTemplate.delete(key);
        try {
            StoredRefreshPrincipal stored = objectMapper.readValue(value, StoredRefreshPrincipal.class);
            if (stored.scope() != scope) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedPrincipal(stored.id(), stored.scope(), stored.name()));
        } catch (java.io.IOException exception) {
            return Optional.empty();
        }
    }

    public void revoke(AuthScope scope, String token) {
        stringRedisTemplate.delete(key(scope, token));
    }

    public Duration refreshTtl() {
        return authProperties.getRefreshTokenTtl();
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return BASE64_URL_ENCODER.encodeToString(bytes);
    }

    private String key(AuthScope scope, String token) {
        return "auth:refresh:" + scope.name().toLowerCase() + ":" + sha256(token);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return BASE64_URL_ENCODER.encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Refresh token hash를 생성하지 못했습니다.", exception);
        }
    }

    private record StoredRefreshPrincipal(
            Long id,
            AuthScope scope,
            String name
    ) {
    }
}
