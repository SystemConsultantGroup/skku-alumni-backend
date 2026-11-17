package com.scg.alumni.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scg.alumni.domain.access.AccessPermissionCache;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Transactional
class MemberPermissionIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessPermissionCache cache;
    @MockitoBean StringRedisTemplate redis;

    @BeforeEach void configureRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        java.util.Map<String, String> entries = new java.util.HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> { entries.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        when(values.increment(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            long next = Long.parseLong(entries.getOrDefault(key, "0")) + 1;
            entries.put(key, Long.toString(next));
            return next;
        });
    }

    @AfterEach void clearCachedPermission() { cache.invalidate(1L); }

    @Test
    void currentPermissionIsAppliedToExistingJwtOnEveryRequest() throws Exception {
        jdbc.update("update payment_records set status = 'UNPAID' where user_id = 1 and officer_term_id = 3");
        // Direct JDBC updates bypass the application's after-commit invalidation.
        cache.invalidate(1L);
        String token = tokens.issue(new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Member")).token();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        mvc.perform(get("/api/v1/notices").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        jdbc.update("update payment_records set status = 'PAID' where user_id = 1 and officer_term_id = 3");
        cache.invalidate(1L);
        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("update users set account_blocked = true where id = 1");
        cache.invalidate(1L);
        mvc.perform(get("/api/v1/notices").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
}
