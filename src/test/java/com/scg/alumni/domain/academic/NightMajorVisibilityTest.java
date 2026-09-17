package com.scg.alumni.domain.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.scg.alumni.api.auth.AuthController;
import com.scg.alumni.api.member.MemberDirectoryService;
import com.scg.alumni.api.member.MemberSummaryResponse;
import com.scg.alumni.api.operations.ReferenceDataController;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.List;
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
    private ReferenceDataController referenceDataController;

    @Autowired
    private MajorCatalog majorCatalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void makeNightSchoolMember() {
        makeNightSchoolMember(3L);
    }

    /** 동문 목록에 나오는 사람은 시드의 1번이다(3번은 현행 임기 납부자가 아니라 목록에 없다). */
    private void makeNightSchoolMember(long userId) {
        jdbcTemplate.update("""
                insert into majors (id, name, normalized_name, status, college_id, created_at, updated_at)
                values (900, '(야)법학과', '(야)법학과', 'ACTIVE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("update users set major_id = 900 where id = ?", userId);
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
        makeNightSchoolMember(1L);

        assertThat(memberDirectoryService.search(null, null, null, null, null, null, null, null, null, null, null, 50, null)
                .items())
                .filteredOn(item -> item.id() == 1L)
                .hasSize(1)
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

    /** 로그인 없이 열리는 학과 목록에 야간 학과가 따로 나오면, 그 항목으로 야간 졸업생을 골라낼 수 있다. */
    @Test
    @Transactional
    void publicMajorListHasNoNightEntry() {
        makeNightSchoolMember();

        // 컨트롤러 전체는 H2 에 없는 substring_index 를 써서 학과 목록만 부른다. 컨트롤러는 이 목록을 그대로 싣는다.
        List<Map<String, Object>> majors = majorCatalog.memberOptions();

        assertThat(majors).extracting(major -> (String) major.get("name"))
                .noneMatch(name -> name.contains("(야"))
                .containsOnlyOnce("법학과");
        assertThat(majors).extracting(major -> (Long) major.get("id")).doesNotContain(900L);
    }

    /** 관리자는 저장된 이름 그대로 본다. */
    @Test
    @Transactional
    void adminMajorListKeepsTheNightMarker() {
        makeNightSchoolMember();

        assertThat(referenceDataController.findAdminMajors())
                .extracting(major -> (String) major.get("name"))
                .contains("(야)법학과", "법학과");
    }

    /** 주간 학과로 거르면 야간 졸업생도 함께 나온다. 따로 거를 방법이 없어야 한다. */
    @Test
    @Transactional
    void filteringByTheDaytimeMajorIncludesNightSchoolMembers() {
        makeNightSchoolMember(1L);
        Long dayMajorId = jdbcTemplate.queryForObject("select id from majors where name = '법학과'", Long.class);

        assertThat(directoryIds(null, null, dayMajorId)).contains(1L);
    }

    /** 검색어 '(야)' 가 저장된 이름에 걸리면 결과가 곧 야간 졸업생 명단이 된다. */
    @Test
    @Transactional
    void searchingForTheNightMarkerFindsNobody() {
        makeNightSchoolMember(1L);

        assertThat(directoryIds("(야)", null, null)).doesNotContain(1L);
        assertThat(directoryIds("(야)", "major", null)).doesNotContain(1L);
        assertThat(directoryIds("법학과", "major", null)).contains(1L);
    }

    private List<Long> directoryIds(String keyword, String searchType, Long majorId) {
        return memberDirectoryService.search(keyword, searchType, majorId, null, null, null, null, null, null, null,
                        null, 50, null)
                .items().stream().map(MemberSummaryResponse::id).toList();
    }
}
