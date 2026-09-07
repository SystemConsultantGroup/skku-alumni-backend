package com.scg.alumni.api.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임원정보의 학과 거르기.
 *
 * <p>학과 목록에는 이름이 바뀐 옛 학과도 그대로 남겨 둔다. 졸업할 때의 이름으로
 * 찾는 사람이 있기 때문이다. 그러니 어느 이름을 골라도 같은 사람들이 나와야 한다.
 */
@SpringBootTest
class MemberDirectoryMajorFilterTest {

    @Autowired
    private MemberDirectoryService memberDirectoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 회원은 현재 이름(시스템경영공학과)으로 저장돼 있고, 목록에서 옛 이름(산업공학과)을 골랐을 때. */
    @Test
    @Transactional
    void oldNameFindsMemberStoredUnderTheCurrentName() {
        moveMemberToMajor(2L, "시스템경영공학과");

        assertThat(namesFilteredBy("산업공학과")).contains("김명륜");
    }

    /** 반대 방향. 회원은 옛 이름으로 저장돼 있고, 목록에서 현재 이름을 골랐을 때. */
    @Test
    @Transactional
    void currentNameFindsMemberStoredUnderTheOldName() {
        moveMemberToMajor(2L, "산업공학과");

        assertThat(namesFilteredBy("시스템경영공학과")).contains("김명륜");
    }

    /** 어느 이름을 골라도 결과가 같아야 한다. */
    @Test
    @Transactional
    void bothNamesReturnTheSamePeople() {
        assertThat(namesFilteredBy("산업공학과")).isEqualTo(namesFilteredBy("시스템경영공학과"));
    }

    /** 관계없는 학과를 고르면 나오지 않는다. */
    @Test
    @Transactional
    void unrelatedMajorDoesNotMatch() {
        moveMemberToMajor(2L, "시스템경영공학과");

        assertThat(namesFilteredBy("법학과")).doesNotContain("김명륜");
    }

    private void moveMemberToMajor(Long memberId, String majorName) {
        jdbcTemplate.update("update users set major_id = ? where id = ?", majorId(majorName), memberId);
    }

    private List<String> namesFilteredBy(String majorName) {
        return memberDirectoryService
                .search(null, null, majorId(majorName), null, null, null, null, null, null, null, null, 50)
                .items()
                .stream()
                .map(MemberSummaryResponse::name)
                .toList();
    }

    private Long majorId(String majorName) {
        return jdbcTemplate.queryForObject("select id from majors where name = ?", Long.class, majorName);
    }
}
