package com.scg.alumni.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.api.operations.AdminMemberPasswordController;
import com.scg.alumni.api.operations.UserFeatureController;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import com.scg.alumni.support.RedislessTestConfig;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호를 잊은 회원을 다시 들여보내는 두 갈래.
 *
 * <p>사무처가 새 비밀번호를 정해 주는 길과, 회원이 직접 바꾸는 길이다. 메일·문자로
 * 링크를 보내는 통로가 생기기 전까지는 앞의 길이 유일한 구제 수단이다.
 */
@SpringBootTest
@Import(RedislessTestConfig.class)
class PasswordResetTest {

    @Autowired
    private AdminMemberPasswordController adminMemberPasswordController;

    @Autowired
    private UserFeatureController userFeatureController;

    @Autowired
    private AuthController authController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 사무처가 정해 준 비밀번호로 들어오고, 잊어버린 옛 비밀번호로는 못 들어온다. */
    @Test
    @Transactional
    void resetLetsTheMemberInWithTheNewPasswordOnly() {
        signInAsAdmin();
        registerAccount(1L, "park.self");

        adminMemberPasswordController.resetPassword(1L, resetRequest("N3wpass!"));

        assertThatCode(() -> login("park.self", "N3wpass!")).doesNotThrowAnyException();
        assertThatThrownBy(() -> login("park.self", "Passw0rd!"))
                .hasMessageContaining("아이디 또는 비밀번호를 확인해주세요");
    }

    /**
     * 아이디를 만든 적 없는 회원.
     *
     * <p>엑셀로 부어 넣은 명단은 아이디도 비밀번호도 없다. 비밀번호만 넣어 주면
     * 학번으로 들어올 수 있으므로, 무엇으로 로그인하는지 응답에 담아 돌려준다.
     */
    @Test
    @Transactional
    void memberWithoutALoginIdSignsInWithTheStudentId() {
        signInAsAdmin();
        clearAccount(3L, "ACTIVE");
        String studentId = jdbcTemplate.queryForObject(
                "select student_id from users where id = 3", String.class);

        Map<String, Object> result = adminMemberPasswordController.resetPassword(3L, resetRequest("N3wpass!"));

        assertThat(result.get("loginId")).isNull();
        assertThat(result.get("studentId")).isEqualTo(studentId);
        assertThatCode(() -> login(studentId, "N3wpass!")).doesNotThrowAnyException();
    }

    /** 누가 누구의 비밀번호를 건드렸는지는 남는다. 무엇으로 바꿨는지는 남기지 않는다. */
    @Test
    @Transactional
    void resetIsRecordedInTheAuditLog() {
        signInAsAdmin();
        registerAccount(1L, "park.self");

        adminMemberPasswordController.resetPassword(1L, resetRequest("N3wpass!"));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from admin_audit_logs
                where action = 'RESET_MEMBER_PASSWORD' and target_type = 'user' and target_id = 1
                """, Integer.class)).isOne();
    }

    /** 지운 회원은 대상이 아니다. 되살린 뒤에야 비밀번호를 정해 줄 수 있다. */
    @Test
    @Transactional
    void deletedMemberCannotBeReset() {
        signInAsAdmin();
        jdbcTemplate.update("update users set deleted_at = CURRENT_TIMESTAMP where id = 1");

        assertThatThrownBy(() -> adminMemberPasswordController.resetPassword(1L, resetRequest("N3wpass!")))
                .hasMessageContaining("삭제된 회원입니다");
    }

    /** 탈퇴한 회원은 이름·학번까지 비워진다. 비밀번호를 넣어도 로그인할 계정이 되지 않는다. */
    @Test
    @Transactional
    void withdrawnMemberCannotBeReset() {
        signInAsAdmin();
        jdbcTemplate.update("update users set status = 'WITHDRAWN' where id = 1");

        assertThatThrownBy(() -> adminMemberPasswordController.resetPassword(1L, resetRequest("N3wpass!")))
                .hasMessageContaining("탈퇴한 회원입니다");
    }

    /** 본인이 바꿀 때는 지금 쓰는 비밀번호를 맞혀야 한다. */
    @Test
    @Transactional
    void changingMyOwnPasswordRequiresTheCurrentOne() {
        registerAccount(1L, "park.self");
        signInAsMember(1L);

        assertThatThrownBy(() -> userFeatureController.changeMyPassword(
                new UserFeatureController.PasswordChangeRequest("wrong!", "N3wpass!")))
                .hasMessageContaining("현재 비밀번호가 맞지 않습니다");

        userFeatureController.changeMyPassword(
                new UserFeatureController.PasswordChangeRequest("Passw0rd!", "N3wpass!"));
        assertThatCode(() -> login("park.self", "N3wpass!")).doesNotThrowAnyException();
    }

    /** 같은 비밀번호를 다시 넣는 것은 바꾼 것이 아니다. */
    @Test
    @Transactional
    void changingToTheSamePasswordIsRejected() {
        registerAccount(1L, "park.self");
        signInAsMember(1L);

        assertThatThrownBy(() -> userFeatureController.changeMyPassword(
                new UserFeatureController.PasswordChangeRequest("Passw0rd!", "Passw0rd!")))
                .hasMessageContaining("다른 비밀번호를 정해주세요");
    }

    private AdminMemberPasswordController.PasswordResetRequest resetRequest(String password) {
        return new AdminMemberPasswordController.PasswordResetRequest(password);
    }

    private void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "안상인"), null));
    }

    private void signInAsMember(long memberId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(memberId, AuthScope.MEMBER, "박성균"), null));
    }

    /** 시드 비밀번호 값에 기대지 않고 직접 심는다. */
    private void registerAccount(long memberId, String loginId) {
        jdbcTemplate.update("update users set login_id = ?, password = ?, status = 'ACTIVE' where id = ?",
                loginId, passwordEncoder.encode("Passw0rd!"), memberId);
    }

    private void clearAccount(long memberId, String status) {
        jdbcTemplate.update(
                "update users set login_id = null, password = '', status = ? where id = ?", status, memberId);
    }

    private void login(String identifier, String password) {
        authController.memberLogin(new AuthController.LoginRequest(identifier, password),
                new MockHttpServletRequest(), new MockHttpServletResponse());
    }
}
