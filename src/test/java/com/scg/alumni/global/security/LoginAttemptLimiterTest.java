package com.scg.alumni.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LoginAttemptLimiterTest {
    @Test
    void accountAndIdentifierCountersCannotCollide() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), org.mockito.ArgumentMatchers.<List<String>>any())).thenReturn(1L);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(redis);
        limiter.check(AuthScope.MEMBER, "account:1");
        limiter.checkAccount(AuthScope.MEMBER, 1);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(2)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }
    @Test
    void excessiveAttemptsAreRejectedAndRedisFailureIsClosed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(redis);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), org.mockito.ArgumentMatchers.<List<String>>any()))
                .thenReturn(11L).thenReturn(null);
        assertThatThrownBy(() -> limiter.check(AuthScope.MEMBER, "Example"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> limiter.check(AuthScope.MEMBER, "Example"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
