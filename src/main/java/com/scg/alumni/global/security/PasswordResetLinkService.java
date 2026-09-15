package com.scg.alumni.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 비밀번호 재설정 링크에 싣는 1회용 토큰.
 *
 * <p>사무처가 링크를 받아 문자·메일·메신저 무엇으로든 회원에게 전한다. 발송을
 * 서버가 하지 않으므로 링크가 어디를 거쳐 갈지 알 수 없다. 그래서 세 가지를 지킨다.
 * 토큰은 원문을 저장하지 않고 해시로만 둔다(Redis 가 새어도 링크를 되살릴 수 없다).
 * 한 번 쓰면 사라진다. 같은 회원에게 새 링크를 만들면 앞의 링크는 죽는다 —
 * 잘못 보낸 링크를 되돌리는 방법이 "다시 발급" 하나로 끝난다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetLinkService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate stringRedisTemplate;
    private final AuthProperties authProperties;

    /** 새 토큰을 만들고 이 회원의 이전 토큰을 지운다. 돌려주는 값이 링크에 실리는 원문이다. */
    public String issue(Long memberId) {
        String previousHash = stringRedisTemplate.opsForValue().get(memberKey(memberId));
        if (previousHash != null) {
            stringRedisTemplate.delete(tokenKey(previousHash));
        }

        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String token = BASE64_URL_ENCODER.encodeToString(bytes);
        String hash = sha256(token);
        Duration ttl = ttl();
        stringRedisTemplate.opsForValue().set(tokenKey(hash), memberId.toString(), ttl);
        stringRedisTemplate.opsForValue().set(memberKey(memberId), hash, ttl);
        return token;
    }

    /** 살아 있는 토큰이면 그 회원 id. 쓰지는 않는다 — 화면이 누구의 비밀번호인지 먼저 보여준다. */
    public Optional<Long> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = stringRedisTemplate.opsForValue().get(tokenKey(sha256(token.trim())));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public void consume(String token, Long memberId) {
        stringRedisTemplate.delete(tokenKey(sha256(token.trim())));
        stringRedisTemplate.delete(memberKey(memberId));
    }

    public Duration ttl() {
        return authProperties.getPasswordResetLinkTtl();
    }

    private String tokenKey(String hash) {
        return "auth:password-reset:token:" + hash;
    }

    private String memberKey(Long memberId) {
        return "auth:password-reset:member:" + memberId;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return BASE64_URL_ENCODER.encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("재설정 토큰 해시를 만들지 못했습니다.", exception);
        }
    }
}
