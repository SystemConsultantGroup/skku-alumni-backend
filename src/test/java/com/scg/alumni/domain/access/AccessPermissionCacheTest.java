package com.scg.alumni.domain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AccessPermissionCacheTest {
    @Test
    @SuppressWarnings("unchecked")
    void cacheHitDoesNotConsultDatabase() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(startsWith("auth:access:v3:"))).thenReturn("FULL");
        var database = mock(AccessQualificationService.class);
        when(database.levelAt(eq(1L), any(LocalDate.class))).thenReturn(AccessLevel.FULL);
        var cache = new AccessPermissionCache(redis, database,
                new OfficerTermPolicyProperties(7, ZoneId.of("Asia/Seoul")));

        assertThat(cache.currentLevel(1)).isEqualTo(AccessLevel.FULL);
        verifyNoInteractions(database);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missAndRedisFailureUseDatabase() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(startsWith("auth:access:v3:"))).thenReturn(null).thenThrow(new IllegalStateException("redis unavailable"));
        var database = mock(AccessQualificationService.class);
        when(database.levelAt(eq(1L), any(LocalDate.class))).thenReturn(AccessLevel.NONE, AccessLevel.LIMITED)
                .thenThrow(new IllegalStateException("database unavailable"));
        var cache = new AccessPermissionCache(redis, database,
                new OfficerTermPolicyProperties(7, ZoneId.of("Asia/Seoul")));

        assertThat(cache.currentLevel(1)).isEqualTo(AccessLevel.NONE);
        verify(values).set(contains(":1"), eq("NONE"), any(java.time.Duration.class));
        assertThat(cache.currentLevel(1)).isEqualTo(AccessLevel.LIMITED);
        verify(database, times(2)).levelAt(eq(1L), any(LocalDate.class));
        assertThatThrownBy(() -> cache.currentLevel(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesInvalidationAfterRedisFailure() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment("auth:access:user-generation:1")).thenThrow(new IllegalStateException()).thenReturn  (1L);
        var cache = new AccessPermissionCache(redis, mock(AccessQualificationService.class),
                new OfficerTermPolicyProperties(7, ZoneId.of("Asia/Seoul")));
        cache.invalidate(1L);
        verify(values, times(2)).increment("auth:access:user-generation:1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulInvalidationCausesNextReadToRecalculate() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        var store = new java.util.HashMap<String, String>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> store.get(call.getArgument(0)));
        doAnswer(call -> { store.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        when(values.increment(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            return Long.valueOf(store.compute(key, (ignored, value) -> value == null ? "1" : Long.toString(Long.parseLong(value) + 1)));
        });
        var database = mock(AccessQualificationService.class);
        when(database.levelAt(eq(1L), any(LocalDate.class))).thenReturn(AccessLevel.LIMITED, AccessLevel.FULL);
        when(database.levelAt(eq(2L), any(LocalDate.class))).thenReturn(AccessLevel.FULL);
        var cache = new AccessPermissionCache(redis, database,
                new OfficerTermPolicyProperties(7, ZoneId.of("Asia/Seoul")));
        assertThat(cache.currentLevel(1)).isEqualTo(AccessLevel.LIMITED);
        assertThat(cache.currentLevel(1)).isEqualTo(AccessLevel.LIMITED);
        assertThat(cache.currentLevel(2)).isEqualTo(AccessLevel.FULL);
        cache.invalidate(1L);
        assertThat(cache.currentLevel(1)).isEqualTo(AccessLevel.FULL);
        assertThat(cache.currentLevel(2)).isEqualTo(AccessLevel.FULL);
        verify(database, times(2)).levelAt(eq(1L), any(LocalDate.class));
        verify(database, times(1)).levelAt(eq(2L), any(LocalDate.class));
    }
}
