package com.scg.alumni.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아이디·비밀번호는 회원이 직접 정한다.
 *
 * <p>킹고아이디는 사무처가 명단에 적어 넣는 참고값이고 로그인에 쓰지 않는다.
 * 두 값이 한 칸을 나눠 쓰던 때에는, 사무처가 엑셀에 킹고아이디를 채워 올리면
 * 그 회원은 아이디를 고를 기회를 잃었다.
 */
@SpringBootTest
@Import(com.scg.alumni.support.RedislessTestConfig.class)
class MemberAccountRegistrationTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /** 킹고아이디로는 로그인할 수 없다. 회원이 정한 아이디로만 들어온다. */
    @Test
    @Transactional
    void kingoIdIsNotALoginIdentifier() {
        registerAccount(1L, "park.self");

        assertThatThrownBy(() -> login("skku.park", "Passw0rd!"))
                .hasMessageContaining("아이디 또는 비밀번호를 확인해주세요");
        assertThatCode(() -> login("park.self", "Passw0rd!")).doesNotThrowAnyException();
    }

    /** 학번과 이메일은 그대로 로그인에 쓸 수 있다. 아이디를 새로 만든 회원이 끊기면 안 된다. */
    @Test
    @Transactional
    void studentIdAndEmailStillLogIn() {
        registerAccount(1L, "park.self");

        assertThatCode(() -> login("1998310001", "Passw0rd!")).doesNotThrowAnyException();
        assertThatCode(() -> login("park@example.com", "Passw0rd!")).doesNotThrowAnyException();
    }

    /**
     * 엑셀로 부어 넣고 승인까지 마친 회원.
     *
     * <p>아이디도 비밀번호도 없이 활성 상태가 된다. 여기서 계정 만들기를 막으면
     * 로그인도 가입도 못 하는 막다른 골목에 갇힌다.
     */
    @Test
    @Transactional
    void activeMemberWithoutAnAccountCanStillRegisterOne() {
        clearAccount(3L, "ACTIVE");

        AuthController.SignupResponse response = signUp(3L, "이율전", 1995, "법학과", "010-1000-0003", "lee.self");

        // 회비 확인까지 끝난 계정을 아이디를 만들었다는 이유로 승인 대기로 되돌리면 안 된다.
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(accountOf(3L).get("status")).isEqualTo("ACTIVE");
        assertThatCode(() -> login("lee.self", "Passw0rd!")).doesNotThrowAnyException();
    }

    /** 아직 승인 전인 회원은 그대로 승인 대기로 남는다. */
    @Test
    @Transactional
    void pendingMemberStaysPending() {
        clearAccount(3L, "PENDING");

        assertThat(signUp(3L, "이율전", 1995, "법학과", "010-1000-0003", "lee.self").status())
                .isEqualTo("PENDING");
    }

    /** 이미 아이디와 비밀번호가 있으면 남이 가로챌 수 없다. */
    @Test
    @Transactional
    void accountCannotBeRegisteredTwice() {
        registerAccount(3L, "lee.self");

        assertThatThrownBy(() -> signUp(3L, "이율전", 1995, "법학과", "010-1000-0003", "lee.other"))
                .hasMessageContaining("이미 아이디와 비밀번호가 등록된 계정입니다");
    }

    /** 킹고아이디가 있어도 아이디를 만들지 않았으면 계정 미등록이다. */
    @Test
    @Transactional
    void kingoIdAloneDoesNotCountAsARegisteredAccount() {
        clearAccount(3L, "ACTIVE");
        jdbcTemplate.update("update users set kingo_id = 'skku.lee' where id = 3");

        Map<String, Object> account = accountOf(3L);
        assertThat(account.get("kingo_id")).isEqualTo("skku.lee");
        assertThat(account.get("login_id")).isNull();
    }

    /** 아이디·비밀번호를 지워 계정 미등록 상태로 되돌린다. 킹고아이디는 그대로 둔다. */
    private void clearAccount(long memberId, String status) {
        jdbcTemplate.update(
                "update users set login_id = null, password = '', status = ? where id = ?", status, memberId);
    }

    /** 회원이 계정을 만들어 둔 상태. 시드 비밀번호 값에 기대지 않고 직접 심는다. */
    private void registerAccount(long memberId, String loginId) {
        clearAccount(memberId, "ACTIVE");
        jdbcTemplate.update("update users set login_id = ?, password = ? where id = ?",
                loginId, passwordEncoder.encode("Passw0rd!"), memberId);
    }

    private Map<String, Object> accountOf(long memberId) {
        return jdbcTemplate.queryForMap(
                "select login_id, kingo_id, status, password from users where id = ?", memberId);
    }

    private AuthController.SignupResponse signUp(
            long expectedId, String name, int admissionYear, String majorName, String phone, String loginId) {
        AuthController.SignupResponse response = authController.memberSignup(
                new AuthController.SignupRequest(loginId, name, admissionYear, majorName, phone, "Passw0rd!"));
        assertThat(response.id()).isEqualTo(expectedId);
        return response;
    }

    private void login(String identifier, String password) {
        authController.memberLogin(new AuthController.LoginRequest(identifier, password),
                new MockHttpServletRequest(), new MockHttpServletResponse());
    }
}
