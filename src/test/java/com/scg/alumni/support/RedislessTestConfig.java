package com.scg.alumni.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 테스트에서 Redis 자리를 메우는 메모리 저장소.
 *
 * <p>로그인은 refresh token 을 Redis 에 넣는다. 그래서 로그인을 부르는 테스트는
 * 살아 있는 Redis 없이는 실패하는데, 이 실패는 개발자 기계에서 잘 드러나지 않는다.
 * 6379 에 다른 프로젝트의 Redis 가 떠 있으면 테스트가 그 서버에 붙어 통과해버리기
 * 때문이다. 실제로 그렇게 통과하던 테스트가 CI 에서만 깨졌다.
 *
 * <p>테스트 결과가 바깥에 무엇이 떠 있느냐에 달려 있으면 안 된다. 붙을 곳을 안으로
 * 들여 그 조건을 없앤다.
 *
 * <p>넣고 빼는 동작은 실제로 맵에 남긴다. 값을 흘려버리면 발급한 토큰을 다시 쓰는
 * 흐름이 조용히 통과해, 나중에 그 흐름을 검증하려는 테스트가 무엇을 확인하는지
 * 알 수 없게 된다. 만료는 두지 않는다 — 테스트 한 판이 도는 동안 토큰이 늙어
 * 사라지는 일은 없다.
 */
@TestConfiguration
public class RedislessTestConfig {

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    StringRedisTemplate stringRedisTemplate() {
        Map<String, String> store = new ConcurrentHashMap<>();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);

        given(template.opsForValue()).willReturn(values);
        willAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(values).set(anyString(), anyString(), any(Duration.class));
        given(values.get(anyString())).willAnswer(invocation -> store.get(invocation.<String>getArgument(0)));
        given(template.delete(anyString())).willAnswer(invocation -> store.remove(invocation.<String>getArgument(0)) != null);

        return template;
    }
}
