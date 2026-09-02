package com.scg.alumni.domain.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.scg.alumni.api.auth.AuthController;
import com.scg.alumni.api.member.MemberDirectoryService;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 야간 표기는 동문에게 보이지 않는다.
 *
 * <p>주간·야간은 같은 학과이고, 동문끼리 보는 화면에 야간 여부를 드러내지 않기로 했다.
 * 시드에는 야간 학과가 없어서 검증용으로 하나 만들어 붙인다. 이어받은 학과를 지정하지
 * 않은 채로 만든다 — 데이터가 그렇게 들어와도 표기가 새지 않아야 한다.
 */
@SpringBootTest
class NightMajorVisibilityTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private MemberDirectoryService memberDirectoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void makeNightSchoolMember() {
        jdbcTemplate.update("""
                insert into majors (id, name, normalized_name, status, college_id, created_at, updated_at)
                values (900, '(야)법학과', '(야)법학과', 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("update users set major_id = 900 where id = 3");
    }

    @BeforeEach
    void signInAsMember() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(3L, AuthScope.MEMBER, "이율전"), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 다섯 개 사용자 화면이 공유하는 학과명 조각.
     *
     * <p>내 정보와 동문 상세는 H2 에 없는 DATE_SUB 를 써서 테스트에서 실행되지 않는다.
     * 그래서 화면을 부르는 대신 조각 자체를 확인한다. 다섯 질의가 모두 이 조각을 쓴다.
     */
    @Test
    @Transactional
    void theSharedMajorNameFragmentHidesTheNightMarker() {
        makeNightSchoolMember();

        String stored = jdbcTemplate.queryForObject("""
                select %s
                from users u
                join majors m on m.id = u.major_id
                left join majors dm on dm.id = m.display_major_id
                where u.id = 3
                """.formatted(MajorNames.displayNameColumn("m", "dm")), String.class);

        assertThat(MajorNames.stripNightMarkers(stored, stored)).isEqualTo("법학과");
    }

    /** 이름이 바뀐 학과는 이어받은 이름으로 보인다. 야간 처리와 함께 동작해야 한다. */
    @Test
    @Transactional
    void theSharedMajorNameFragmentFollowsTheRenamedMajor() {
        jdbcTemplate.update("update users set major_id = (select id from majors where name = '법률학과') where id = 3");

        String stored = jdbcTemplate.queryForObject("""
                select %s
                from users u
                join majors m on m.id = u.major_id
                left join majors dm on dm.id = m.display_major_id
                where u.id = 3
                """.formatted(MajorNames.displayNameColumn("m", "dm")), String.class);

        assertThat(MajorNames.stripNightMarkers(stored, stored)).isEqualTo("법학과");
    }

    @Test
    @Transactional
    void directoryHidesTheNightMarker() {
        makeNightSchoolMember();

        assertThat(memberDirectoryService.search(null, null, null, null, null, null, null, null, null, null, null, 50)
                .items())
                .filteredOn(item -> item.id() == 3L)
                .allSatisfy(item -> assertThat(item.majorName()).isEqualTo("법학과"));
    }

    /** 야간 회원이 목록에서 주간 학과를 골라도 본인으로 대조돼야 한다. */
    @Test
    @Transactional
    void nightSchoolMemberSignsUpWithTheDaytimeMajor() {
        makeNightSchoolMember();
        jdbcTemplate.update("update users set kingo_id = null, password = '', status = 'PENDING' where id = 3");

        assertThatCode(() -> {
            AuthController.SignupResponse response = authController.memberSignup(
                    new AuthController.SignupRequest(
                            "night.test", "이율전", 1995, "법학과", "010-1000-0003", "Passw0rd!"));
            assertThat(response.id()).isEqualTo(3L);
        }).doesNotThrowAnyException();
    }
}
