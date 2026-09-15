package com.scg.alumni.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.api.operations.AdminMemberPasswordController;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import com.scg.alumni.support.RedislessTestConfig;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사무처가 재설정 링크를 받아 회원에게 직접 전하고, 회원이 그 링크로 비밀번호를 정한다.
 */
@SpringBootTest
@Import(RedislessTestConfig.class)
class PasswordResetLinkTest {

    @Autowired
    private AdminMemberPasswordController adminMemberPasswordController;

    @Autowired
    private PasswordResetController passwordResetController;

    @Autowired
    private AuthController authController;

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

    /** 링크를 연 회원이 비밀번호를 정하면 그것으로 들어온다. */
    @Test
    @Transactional
    void memberSetsAPasswordThroughTheLink() {
        Map<String, Object> link = adminMemberPasswordController.issueResetLink(1L);
        String token = tokenOf(link);

        assertThat(passwordResetController.verify(new PasswordResetController.TokenRequest(token)).get("name"))
                .isEqualTo("박성균");
        passwordResetController.confirm(new PasswordResetController.ConfirmRequest(token, "L1nkpass!"));

        String studentId = jdbcTemplate.queryForObject("select student_id from users where id = 1", String.class);
        assertThatCode(() -> login(studentId, "L1nkpass!")).doesNotThrowAnyException();
    }

    /** 사무처가 어느 통로로 보낼지 고를 수 있게 연락처를 함께 준다. 토큰은 # 뒤에 실린다. */
    @Test
    @Transactional
    void linkComesWithTheMembersContactsAndKeepsTheTokenInTheFragment() {
        jdbcTemplate.update("update users set phone = '010-1000-0001', email = 'park@example.com' where id = 1");

        Map<String, Object> link = adminMemberPasswordController.issueResetLink(1L);

        assertThat((String) link.get("url")).startsWith("http://localhost:4000/reset-password#");
        assertThat(link.get("phone")).isEqualTo("010-1000-0001");
        assertThat(link.get("email")).isEqualTo("park@example.com");
        assertThat(link.get("expiresAt")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from admin_audit_logs
                where action = 'ISSUE_MEMBER_PASSWORD_RESET_LINK' and target_id = 1
                """, Integer.class)).isOne();
    }

    /** 한 번 쓴 링크는 다시 쓸 수 없다. 문자함에 남은 링크를 남이 열어도 소용없어야 한다. */
    @Test
    @Transactional
    void linkWorksOnlyOnce() {
        String token = tokenOf(adminMemberPasswordController.issueResetLink(1L));
        passwordResetController.confirm(new PasswordResetController.ConfirmRequest(token, "L1nkpass!"));

        assertThatThrownBy(() -> passwordResetController.confirm(
                new PasswordResetController.ConfirmRequest(token, "Other12!")))
                .hasMessageContaining("만료되었거나 이미 사용한 링크입니다");
    }

    /** 다시 발급하면 앞의 링크는 죽는다. 엉뚱한 사람에게 보냈을 때 되돌리는 방법이다. */
    @Test
    @Transactional
    void reissuingKillsThePreviousLink() {
        String first = tokenOf(adminMemberPasswordController.issueResetLink(1L));
        String second = tokenOf(adminMemberPasswordController.issueResetLink(1L));

        assertThatThrownBy(() -> passwordResetController.verify(new PasswordResetController.TokenRequest(first)))
                .hasMessageContaining("만료되었거나 이미 사용한 링크입니다");
        assertThatCode(() -> passwordResetController.verify(new PasswordResetController.TokenRequest(second)))
                .doesNotThrowAnyException();
    }

    /** 링크를 만든 뒤 회원이 지워졌으면 그 링크로는 비밀번호를 정할 수 없다. */
    @Test
    @Transactional
    void linkStopsWorkingOnceTheMemberIsDeleted() {
        String token = tokenOf(adminMemberPasswordController.issueResetLink(1L));
        jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where id = 1");

        assertThatThrownBy(() -> passwordResetController.confirm(
                new PasswordResetController.ConfirmRequest(token, "L1nkpass!")))
                .hasMessageContaining("만료되었거나 이미 사용한 링크입니다");
    }

    @Test
    @Transactional
    void madeUpTokenIsRejected() {
        assertThatThrownBy(() -> passwordResetController.verify(new PasswordResetController.TokenRequest("guess")))
                .hasMessageContaining("만료되었거나 이미 사용한 링크입니다");
    }

    private String tokenOf(Map<String, Object> link) {
        String url = (String) link.get("url");
        return url.substring(url.indexOf('#') + 1);
    }

    private void login(String identifier, String password) {
        authController.memberLogin(new AuthController.LoginRequest(identifier, password),
                new MockHttpServletRequest(), new MockHttpServletResponse());
    }
}
