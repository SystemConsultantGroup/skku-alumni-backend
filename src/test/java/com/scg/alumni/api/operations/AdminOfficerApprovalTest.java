package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@org.springframework.context.annotation.Import(com.scg.alumni.support.RedislessTestConfig.class)
@Transactional
class AdminOfficerApprovalTest {
    @Autowired AdminFeatureController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessQualificationService access;
    @Autowired PasswordEncoder encoder;
    @Autowired UserFeatureController users;
    @Autowired com.scg.alumni.domain.organization.AdministrativePersonService people;
    @Autowired com.scg.alumni.api.auth.AuthController auth;

    @BeforeEach void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "Admin"), null));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void applicationReturnsItsOwnGeneratedId() {
        var result = users.createApplication(new UserFeatureController.MemberApplicationRequest(
                "Fictional Application", null, "010-1234-5678", null, "Fictional Major", 2020,
                "Chair", null, null));
        assertThat(jdbc.queryForObject("select name from member_applications where id = ?", String.class, result.get("id")))
                .isEqualTo("Fictional Application");
    }

    @Test
    void approvalWithChangedRoleCreatesLimitedAccount() {
        jdbc.update("""
                insert into member_applications (name, phone, major_name, admission_year, desired_role, status)
                values ('Fictional Applicant', '010-5555-7890', (select name from majors where id = 1), 2020, 'unused', 'PENDING')
                """);
        Long application = jdbc.queryForObject("select max(id) from member_applications", Long.class);
        Map<String, Object> result = controller.updateApplicationStatus(application,
                new AdminFeatureController.StatusUpdateRequest("APPROVED", 2L, null, true));
        long userId = ((Number) result.get("userId")).longValue();
        assertThat(result.get("initialLoginId")).isEqualTo("Fictional Applicant7890");
        assertThat(encoder.matches("1111", jdbc.queryForObject("select password from users where id = ?", String.class, userId))).isTrue();
        assertThat(jdbc.queryForObject("select officer_role_id from officer_histories where user_id = ?", Long.class, userId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, userId)).isEqualTo("PENDING");
        assertThat(access.currentLevel(userId)).isEqualTo(AccessLevel.LIMITED);
        var login = auth.memberLogin(new com.scg.alumni.api.auth.AuthController.LoginRequest((String) result.get("initialLoginId"), "1111"),
                new org.springframework.mock.web.MockHttpServletRequest(), new org.springframework.mock.web.MockHttpServletResponse());
        assertThat(login.id()).isEqualTo(userId);
        long paymentId = jdbc.queryForObject("select id from payment_records where user_id = ?", Long.class, userId);
        controller.updatePaymentStatus(paymentId, new AdminFeatureController.StatusUpdateRequest("PAID"));
        assertThat(access.currentLevel(userId)).isEqualTo(AccessLevel.FULL);
    }

    @Test
    void paymentMemberSearchIncludesActiveAndPendingAccounts() {
        var page = controller.findMembers(null, null, "ELIGIBLE", null, false, false, 1, 50);
        assertThat(page.items()).extracting(row -> ((Number) row.get("id")).longValue()).contains(1L, 5L);
    }

    @Test
    void legacyApprovalRequestWorksWhenNoCandidatesExist() {
        jdbc.update("""
                insert into member_applications (name, phone, major_name, admission_year, desired_role, status)
                values ('Fictional Unique Applicant', '010-9876-5319', (select name from majors where id = 1), 2020,
                        (select name from officer_roles where id = 2), 'PENDING')
                """);
        Long application = jdbc.queryForObject("select max(id) from member_applications", Long.class);
        var result = controller.updateApplicationStatus(application,
                new AdminFeatureController.StatusUpdateRequest("APPROVED", null, null, false));
        assertThat(access.currentLevel(((Number) result.get("userId")).longValue())).isEqualTo(AccessLevel.LIMITED);
    }

    @Test
    void legacyApprovalStillRequiresReviewWhenCandidateExists() {
        jdbc.update("""
                insert into member_applications (name, phone, major_name, admission_year, desired_role, status)
                select name, phone, (select name from majors where id = 1), 2020,
                       (select name from officer_roles where id = 2), 'PENDING' from users where id = 1
                """);
        Long application = jdbc.queryForObject("select max(id) from member_applications", Long.class);
        assertThatThrownBy(() -> controller.updateApplicationStatus(application,
                new AdminFeatureController.StatusUpdateRequest("APPROVED", null, null, false)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select status from member_applications where id = ?", String.class, application)).isEqualTo("PENDING");
    }

    @Test
    void legacyApprovalReusesOnlyAnExactUniqueIdentity() {
        long existing = people.create("Fictional Exact", "EXACT-STUDENT-123", "010-3388-7788", true);
        String originalPassword = jdbc.queryForObject("select password from users where id = ?", String.class, existing);
        jdbc.update("""
                insert into member_applications (name, student_id, phone, major_name, admission_year, desired_role, status)
                values ('Fictional Exact', 'EXACT-STUDENT-123', '010-3388-7788',
                    (select name from majors where id = 1), 2020, (select name from officer_roles where id = 2), 'PENDING')
                """);
        Long application = jdbc.queryForObject("select max(id) from member_applications", Long.class);
        var result = controller.updateApplicationStatus(application, new AdminFeatureController.StatusUpdateRequest("APPROVED"));
        assertThat(((Number) result.get("userId")).longValue()).isEqualTo(existing);
        assertThat(result).doesNotContainKey("initialLoginId");
        assertThat(jdbc.queryForObject("select password from users where id = ?", String.class, existing)).isEqualTo(originalPassword);
        assertThat(access.currentLevel(existing)).isEqualTo(AccessLevel.LIMITED);
    }
}
