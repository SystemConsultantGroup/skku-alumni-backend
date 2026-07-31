package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class MemberWithdrawalTest {

    @Autowired
    private UserFeatureController userFeatureController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void clubLeaderMustAssignSuccessorBeforeWithdrawal() {
        authenticate(1L, "박성균");

        assertThatThrownBy(userFeatureController::withdrawMe)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("회원 탈퇴 전에 다른 회원을 후임으로 임명해주세요");

        assertThat(userStatus(1L)).isEqualTo("ACTIVE");
    }

    @Test
    @Transactional
    void withdrawalEndsMembershipAndMasksPostAuthor() {
        jdbcTemplate.update("""
                insert into club_members (id, club_id, user_id, club_role, joined_at)
                values (900, 1, 4, 'MEMBER', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                insert into posts (
                    id, user_id, title, body, status, post_kind, industry_id, created_at, updated_at
                ) values (1000, 4, '탈퇴 전 작성글', '본문', 'PUBLISHED', 'BUSINESS', 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        authenticate(4L, "최퇴계");

        Map<String, Object> response = userFeatureController.withdrawMe();
        Map<String, Object> post = userFeatureController.findBusinessPost(1000L);

        assertThat(response.get("status")).isEqualTo("WITHDRAWN");
        assertThat(userStatus(4L)).isEqualTo("WITHDRAWN");
        Map<String, Object> withdrawnMember = jdbcTemplate.queryForMap("""
                select student_id, kingo_id, name, password, birth_date, gender, category, degree,
                       major_id, admission_year, graduation_year, nationality,
                       home_zipcode, home_address1, home_address2, phone, email,
                       industry_id, company_id, job_title, pr_text,
                       work_zipcode, work_address1, work_address2
                from users where id = 4
                """);
        assertThat(withdrawnMember.values()).containsOnlyNulls();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_hobbies where user_id = 4", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_web_links where user_id = 4", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from user_blocks where blocker_id = 4 or blocked_id = 4
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from club_members
                where user_id = 4 and left_at is null
                """, Integer.class)).isZero();
        assertThat(post.get("authorName")).isEqualTo("탈퇴한 사용자");
    }

    private void authenticate(Long memberId, String name) {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(memberId, AuthScope.MEMBER, name);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    private String userStatus(Long memberId) {
        return jdbcTemplate.queryForObject(
                "select status from users where id = ?", String.class, memberId);
    }
}
