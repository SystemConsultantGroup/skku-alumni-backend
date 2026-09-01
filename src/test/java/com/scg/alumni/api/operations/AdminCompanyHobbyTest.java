package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class AdminCompanyHobbyTest {

    @Autowired
    private AdminCompanyController adminCompanyController;

    @Autowired
    private AdminHobbyController adminHobbyController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "안상인"), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 재직 중인 회원이 있는 직장을 지우면 그 회원의 직장 칸이 조용히 비어버린다. */
    @Test
    @Transactional
    void companyWithMembersCannotBeDeleted() {
        assertThatThrownBy(() -> adminCompanyController.deleteCompany(1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("소속된 회원이");
    }

    @Test
    @Transactional
    void companyMembersAreListedForTheDetailScreen() {
        assertThat(adminCompanyController.findCompanyMembers(1L))
                .extracting(row -> row.get("name"))
                .contains("김명륜");
    }

    @Test
    @Transactional
    void emptyCompanyIsSoftDeletedAndRevivedByTheSameName() {
        Map<String, Object> created = adminCompanyController.createCompany(
                new AdminCompanyController.CompanyRequest("새회사", null, null, null, null, null));
        Long id = ((Number) created.get("id")).longValue();

        adminCompanyController.deleteCompany(id);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from companies where id = ?", Integer.class, id)).isOne();

        Map<String, Object> revived = adminCompanyController.createCompany(
                new AdminCompanyController.CompanyRequest("새회사", null, null, null, null, null));
        assertThat(((Number) revived.get("id")).longValue()).isEqualTo(id);
    }

    @Test
    @Transactional
    void hobbyMemberCountsIgnoreDeletedLinksAndMembers() {
        assertThat(memberCountOf(1L)).isEqualTo(2);

        jdbcTemplate.update("update user_hobbies set deleted_at = CURRENT_TIMESTAMP where user_id = 1 and hobby_id = 1");

        assertThat(memberCountOf(1L)).isEqualTo(1);
        assertThat(adminHobbyController.findHobbyMembers(1L))
                .extracting(row -> row.get("name"))
                .containsExactly("이율전");
    }

    @Test
    @Transactional
    void deletedHobbyLeavesTheListButKeepsItsLinks() {
        adminHobbyController.deleteHobby(1L);

        assertThat(adminHobbyController.findHobbies(null, null, 50).items())
                .extracting(row -> row.get("id"))
                .doesNotContain(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_hobbies where hobby_id = 1 and deleted_at is null",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @Transactional
    void hobbyNameCannotCollideWithAnExistingOne() {
        assertThatThrownBy(() -> adminHobbyController.updateHobby(2L,
                new AdminHobbyController.HobbyRequest("골프")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("이미 등록된 취미");
    }

    private int memberCountOf(Long hobbyId) {
        return adminHobbyController.findHobbies(null, null, 50).items().stream()
                .filter(row -> hobbyId.equals(row.get("id")))
                .map(row -> ((Number) row.get("memberCount")).intValue())
                .findFirst()
                .orElseThrow();
    }
}
