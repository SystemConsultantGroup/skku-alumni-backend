package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.web.server.ResponseStatusException;

/** 관리 표의 칸 정렬과 쪽 넘김. */
@SpringBootTest
class AdminTableSortTest {

    @Autowired
    private AdminHobbyController adminHobbyController;

    @Autowired
    private AdminCompanyController adminCompanyController;

    @Autowired
    private AdminFeatureController adminFeatureController;

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

    @Test
    @Transactional
    void hobbiesSortByNameBothWays() {
        assertThat(names(adminHobbyController.findHobbies(null, "name", "asc", null, 50).items()))
                .isSorted();
        assertThat(names(adminHobbyController.findHobbies(null, "name", "desc", null, 50).items()))
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** 인원수처럼 계산된 칸도 정렬돼야 한다. */
    @Test
    @Transactional
    void hobbiesSortByMemberCount() {
        List<Integer> counts = adminHobbyController.findHobbies(null, "memberCount", "desc", null, 50).items()
                .stream().map(row -> ((Number) row.get("memberCount")).intValue()).toList();

        assertThat(counts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** 정렬을 걸어도 쪽을 넘기며 같은 줄이 두 번 나오거나 빠지면 안 된다. */
    @Test
    @Transactional
    void pagingWithSortCoversEveryRowExactlyOnce() {
        List<Object> all = new java.util.ArrayList<>();
        Long cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            var page = adminHobbyController.findHobbies(null, "name", "asc", cursor, 2);
            page.items().forEach(row -> all.add(row.get("id")));
            if (!page.hasNext()) break;
            cursor = page.nextCursor();
        }
        List<Object> everything = adminHobbyController.findHobbies(null, "name", "asc", null, 50)
                .items().stream().map(row -> row.get("id")).toList();

        assertThat(all).containsExactlyElementsOf(everything);
        assertThat(all).doesNotHaveDuplicates();
    }

    /** 빈 칸은 어느 방향이든 뒤로 간다. 빈칸이 위에 쌓이면 표를 읽을 수 없다. */
    @Test
    @Transactional
    void emptyValuesSinkToTheBottom() {
        jdbcTemplate.update("update companies set industry_id = null where id = 1");

        List<Map<String, Object>> rows = adminCompanyController
                .findCompanies(null, null, "industryName", "asc", null, 50).items();

        assertThat(rows.get(rows.size() - 1).get("industryName")).isNull();
    }

    @Test
    @Transactional
    void unknownSortColumnIsRejected() {
        assertThatThrownBy(() -> adminHobbyController.findHobbies(null, "name; drop table hobbies", "asc", null, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("정렬할 수 없는 항목입니다");
    }

    /** 회비는 대·기를 따로 고를 수 있어야 한다. */
    @Test
    @Transactional
    void paymentsFilterByGenerationAndPhase() {
        var all = adminFeatureController.findPayments(null, null, null, null, null, null, null, null, 50);
        var generation40 = adminFeatureController.findPayments(null, null, null, 40, null, null, null, null, 50);
        var phase2 = adminFeatureController.findPayments(null, null, null, 40, 2, null, null, null, 50);

        assertThat(all.items()).isNotEmpty();
        assertThat(generation40.items()).allSatisfy(row -> assertThat(row.get("generation")).isEqualTo(40));
        assertThat(phase2.items()).allSatisfy(row -> {
            assertThat(row.get("generation")).isEqualTo(40);
            assertThat(row.get("phase")).isEqualTo(2);
        });
        assertThat(phase2.items().size()).isLessThanOrEqualTo(generation40.items().size());
    }

    @Test
    @Transactional
    void paymentsSortByAmount() {
        List<Integer> amounts = adminFeatureController
                .findPayments(null, null, null, null, null, "amount", "desc", null, 50).items()
                .stream().map(row -> ((Number) row.get("amount")).intValue()).toList();

        assertThat(amounts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    private List<String> names(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("name"))).toList();
    }
}
