package com.scg.alumni.api.operations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class AdminOfficerTermControllerTest {
    @Autowired WebApplicationContext context;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void createsAnUnselectedTermWithExistingListContract() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "관리자"), null));
        webAppContextSetup(context).build().perform(post("/api/v1/admin/officer-terms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"generation\":900,\"phase\":1,\"startedAt\":\"2040-05-01\",\"endedAt\":\"2041-04-30\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(900))
                .andExpect(jsonPath("$.currentTerm").value(false));
    }
}
