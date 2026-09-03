package com.scg.alumni.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 첫 로그인(계정 만들기)의 학과 대조.
 *
 * <p>화면이 학과를 목록에서 고르게 바뀌면서, 같은 학과의 옛 이름과 현재 이름이
 * 둘 다 목록에 보인다. 어느 쪽을 골라도 같은 사람으로 붙어야 한다.
 */
@SpringBootTest
class MemberSignupMajorMatchingTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 회원이 옛 이름(법률학과)으로 저장돼 있고, 목록에서 현재 이름(법학과)을 골랐을 때. */
    @Test
    @Transactional
    void currentNameMatchesMemberStoredUnderTheOldName() {
        prepareFirstLogin(3L);
        jdbcTemplate.update("update users set major_id = (select id from majors where name = '법률학과') where id = 3");

        assertThatCode(() -> signUp(3L, "이율전", 1995, "법학과", "010-1000-0003"))
                .doesNotThrowAnyException();
    }

    /** 반대 방향. 회원은 현재 이름(법학과)으로 저장돼 있고, 목록에서 옛 이름(법률학과)을 골랐을 때. */
    @Test
    @Transactional
    void oldNameMatchesMemberStoredUnderTheCurrentName() {
        prepareFirstLogin(3L);
        assertThat(storedMajorOf(3L)).isEqualTo("법학과");

        assertThatCode(() -> signUp(3L, "이율전", 1995, "법률학과", "010-1000-0003"))
                .doesNotThrowAnyException();
    }

    /** 폐지된 학과는 이어받은 학과가 없다. 그 이름 그대로 붙어야 한다. */
    @Test
    @Transactional
    void closedMajorStillMatchesByItsOwnName() {
        prepareFirstLogin(4L);
        // 4번 회원은 지난 임기 이력만 있어 가입 자격이 없다. 현행 임기 이력을 붙여 자격을 만든다.
        jdbcTemplate.update("""
                insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, payment_status,
                    created_at, updated_at)
                select 4, id, 4, started_at, 'PAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                from officer_terms where current_term = true
                """);
        assertThat(storedMajorOf(4L)).isEqualTo("무역학과");

        assertThatCode(() -> signUp(4L, "최퇴계", 1990, "무역학과", "010-1000-0004"))
                .doesNotThrowAnyException();
    }

    /** 학과를 목록에서 고르더라도 다른 사람의 학과를 고르면 붙지 않아야 한다. */
    @Test
    @Transactional
    void unrelatedMajorDoesNotMatch() {
        prepareFirstLogin(3L);

        assertThatCode(() -> signUp(3L, "이율전", 1995, "무역학과", "010-1000-0003"))
                .hasMessageContaining("일치하는 동문 정보를 찾을 수 없습니다");
    }

    /** 시드 회원은 이미 비밀번호가 있다. 아이디·비밀번호를 지워 계정 미등록 상태로 되돌린다. */
    private void prepareFirstLogin(long memberId) {
        jdbcTemplate.update(
                "update users set login_id = null, password = '', status = 'PENDING' where id = ?", memberId);
    }

    private String storedMajorOf(long memberId) {
        return jdbcTemplate.queryForObject(
                "select m.name from users u join majors m on m.id = u.major_id where u.id = ?",
                String.class, memberId);
    }

    private void signUp(long expectedId, String name, int admissionYear, String majorName, String phone) {
        AuthController.SignupResponse response = authController.memberSignup(
                new AuthController.SignupRequest(
                        "test.signup" + expectedId, name, admissionYear, majorName, phone, "Passw0rd!"));
        assertThat(response.id()).isEqualTo(expectedId);
    }
}
