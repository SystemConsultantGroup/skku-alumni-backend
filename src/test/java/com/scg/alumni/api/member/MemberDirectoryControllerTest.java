package com.scg.alumni.api.member;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class MemberDirectoryControllerTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void searchReturnsOnlyCurrentPaidOfficers() throws Exception {
        mockMvc.perform(get("/api/v1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
    }

    /**
     * 목록의 기본 순서는 직급이 높은 사람부터.
     *
     * <p>박성균은 부회장, 김명륜은 이사다. 가입 순서(id 역순)로 늘어놓으면 이사가
     * 먼저 나온다.
     */
    @Test
    void searchOrdersByOfficerRoleRankByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("박성균"))
                .andExpect(jsonPath("$.items[0].officerRoleName").value("부회장"))
                .andExpect(jsonPath("$.items[1].name").value("김명륜"))
                .andExpect(jsonPath("$.items[1].officerRoleName").value("이사"));
    }

    /**
     * 직급 순으로 늘어놓아도 다음 쪽이 이어져야 한다.
     *
     * <p>커서는 회원 id 하나뿐이라, 직급 자리를 함께 찾지 못하면 두 번째 쪽이
     * 첫 쪽을 되풀이하거나 통째로 비어 버린다.
     */
    @Test
    void searchContinuesAcrossRolesWithCursor() throws Exception {
        mockMvc.perform(get("/api/v1/members").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("박성균"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value(1));

        mockMvc.perform(get("/api/v1/members").param("size", "1").param("cursor", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("김명륜"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    /**
     * 홈의 "최근 임원" 자리는 직급이 아니라 최근에 들어온 순서.
     *
     * <p>이름이 "최근" 이라 직급 순으로 채우면 화면과 말이 어긋난다.
     */
    @Test
    void searchOrdersByRecencyWhenAsked() throws Exception {
        mockMvc.perform(get("/api/v1/members").param("sort", "recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("김명륜"))
                .andExpect(jsonPath("$.items[1].name").value("박성균"));
    }

    /** 최근 순으로 볼 때도 다음 쪽이 이어져야 한다. */
    @Test
    void searchContinuesWithCursorWhenOrderedByRecency() throws Exception {
        mockMvc.perform(get("/api/v1/members").param("sort", "recent").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("김명륜"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value(2));

        mockMvc.perform(get("/api/v1/members").param("sort", "recent").param("size", "1").param("cursor", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("박성균"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void searchSupportsKeywordAndCursorPagination() throws Exception {
        mockMvc.perform(get("/api/v1/members")
                        .param("keyword", "성균테크")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("김명륜"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }
}
