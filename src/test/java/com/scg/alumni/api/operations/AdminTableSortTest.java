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
        assertThat(names(adminHobbyController.findHobbies(null, "name", "asc", 1, 100).items()))
                .isSorted();
        assertThat(names(adminHobbyController.findHobbies(null, "name", "desc", 1, 100).items()))
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** 인원수처럼 계산된 칸도 정렬돼야 한다. */
    @Test
    @Transactional
    void hobbiesSortByMemberCount() {
        List<Integer> counts = adminHobbyController.findHobbies(null, "memberCount", "desc", 1, 100).items()
                .stream().map(row -> ((Number) row.get("memberCount")).intValue()).toList();

        assertThat(counts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** 정렬을 걸어도 쪽을 넘기며 같은 줄이 두 번 나오거나 빠지면 안 된다. */
    @Test
    @Transactional
    void pagingWithSortCoversEveryRowExactlyOnce() {
        var first = adminHobbyController.findHobbies(null, "name", "asc", 1, 10);
        List<Object> all = new java.util.ArrayList<>();
        for (int page = 1; page <= first.totalPages(); page++) {
            adminHobbyController.findHobbies(null, "name", "asc", page, 10)
                    .items().forEach(row -> all.add(row.get("id")));
        }
        List<Object> everything = adminHobbyController.findHobbies(null, "name", "asc", 1, 100)
                .items().stream().map(row -> row.get("id")).toList();

        assertThat(first.totalElements()).isEqualTo(everything.size());
        assertThat(all).containsExactlyElementsOf(everything);
        assertThat(all).doesNotHaveDuplicates();
    }

    /** 화면이 고를 수 없는 쪽 크기는 기본값으로 돌아가고, 끝을 넘은 쪽은 마지막 쪽으로 당겨진다. */
    @Test
    @Transactional
    void pageSizeIsWhitelistedAndPageIsClamped() {
        assertThat(adminHobbyController.findHobbies(null, null, null, 1, 7).size()).isEqualTo(20);
        assertThat(adminHobbyController.findHobbies(null, null, null, 1, 100000).size()).isEqualTo(20);
        assertThat(adminHobbyController.findHobbies(null, null, null, 1, 50).size()).isEqualTo(50);

        var beyond = adminHobbyController.findHobbies(null, null, null, 9999, 10);
        assertThat(beyond.page()).isEqualTo(beyond.totalPages());
        assertThat(adminHobbyController.findHobbies(null, null, null, -3, 10).page()).isEqualTo(1);
    }

    /** 빈 칸은 어느 방향이든 뒤로 간다. 빈칸이 위에 쌓이면 표를 읽을 수 없다. */
    @Test
    @Transactional
    void emptyValuesSinkToTheBottom() {
        jdbcTemplate.update("update companies set industry_id = null where id = 1");

        List<Map<String, Object>> rows = adminCompanyController
                .findCompanies(null, null, "industryName", "asc", 1, 100).items();

        assertThat(rows.get(rows.size() - 1).get("industryName")).isNull();
    }

    @Test
    @Transactional
    void unknownSortColumnIsRejected() {
        assertThatThrownBy(() -> adminHobbyController.findHobbies(null, "name; drop table hobbies", "asc", 1, 100))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("정렬할 수 없는 항목입니다");
    }

    /** 회비는 대·기를 따로 고를 수 있어야 한다. */
    @Test
    @Transactional
    void paymentsFilterByGenerationAndPhase() {
        var all = adminFeatureController.findPayments(null, null, null, null, null, null, null, null, 1, 100);
        var generation40 = adminFeatureController.findPayments(null, null, null, null, 40, null, null, null, 1, 100);
        var phase2 = adminFeatureController.findPayments(null, null, null, null, 40, 2, null, null, 1, 100);

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
                .findPayments(null, null, null, null, null, null, "amount", "desc", 1, 100).items()
                .stream().map(row -> ((Number) row.get("amount")).intValue()).toList();

        assertThat(amounts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Autowired
    private AdminPushNotificationController adminPushNotificationController;

    /** 모든 표가 필터를 켠 채로도 개수와 쪽 계산을 마쳐야 한다. 개수 쿼리는 조회문을 감싸므로 열 이름이 겹치면 깨진다. */
    @Test
    @Transactional
    void everyPagedTableAnswersWithAndWithoutFilters() {
        var c = adminFeatureController;
        List<com.scg.alumni.api.common.PageResponse<Map<String, Object>>> pages = List.of(
                c.findMembers(null, null, null, null, null, null, 1, 10),
                c.findMembers("김", "PAID", "ACTIVE", 1L, true, true, 1, 10),
                c.findApplications(null, null, 1, 10),
                c.findApplications("김", "PENDING", 1, 10),
                c.findAsisSyncTargets(null, null, null, 1, 10),
                c.findAsisSyncTargets("김", false, "phone", 1, 10),
                c.findReports(null, 1, 10),
                c.findReports("PENDING", 1, 10),
                c.findReportedPosts(null, null, 1, 10),
                c.findReportedPosts("NOTICE", "PENDING", 1, 10),
                c.findReportedPosts("NEWS", "RESOLVED", 1, 10),
                c.findPosts(null, null, null, null, 1, 10),
                c.findPosts("a", "NOTICE", "PUBLISHED", true, 1, 10),
                c.findManagers(null, null, 1, 10),
                c.findManagers(1L, true, 1, 10),
                c.findAuditLogs(null, 1, 10),
                c.findAuditLogs("UPDATE_MEMBER_STATUS", 1, 10),
                c.findPayments(null, "PAID", null, 1L, null, null, null, null, 1, 10),
                adminPushNotificationController.findTargets(null, null, null, 1, 10),
                adminPushNotificationController.findTargets("김", true, 1L, 1, 10),
                adminPushNotificationController.findMessages(1, 10));

        assertThat(pages).allSatisfy(page -> {
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalPages()).isGreaterThanOrEqualTo(1);
            assertThat(page.items().size()).isLessThanOrEqualTo(10);
            assertThat(page.totalElements()).isGreaterThanOrEqualTo(page.items().size());
        });
    }

    private List<String> names(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> String.valueOf(row.get("name"))).toList();
    }
}
