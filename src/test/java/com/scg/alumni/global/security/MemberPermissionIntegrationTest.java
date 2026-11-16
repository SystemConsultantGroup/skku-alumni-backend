package com.scg.alumni.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class MemberPermissionIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter security;
    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void currentPermissionIsAppliedToExistingJwtOnEveryRequest() throws Exception {
        jdbc.update("update payment_records set status = 'UNPAID' where user_id = 1 and officer_term_id = 3");
        String token = tokens.issue(new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Member")).token();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        mvc.perform(get("/api/v1/notices").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        jdbc.update("update payment_records set status = 'PAID' where user_id = 1 and officer_term_id = 3");
        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        jdbc.update("update users set account_blocked = true where id = 1");
        mvc.perform(get("/api/v1/notices").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
}
