package com.scg.alumni.api.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class UnifiedSearchControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void retainsExistingFieldsAndReturnsNotices() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(2L, AuthScope.MEMBER, "가상 동문"), null));
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, '가상통합검색공지', '본문', 'PUBLISHED', 'NOTICE')");
        webAppContextSetup(context).build().perform(get("/api/v1/search").param("keyword", "가상통합검색공지"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortcuts").isArray())
                .andExpect(jsonPath("$.posts").isArray())
                .andExpect(jsonPath("$.people").isArray())
                .andExpect(jsonPath("$.notices[0].title").value("가상통합검색공지"));
    }
}
