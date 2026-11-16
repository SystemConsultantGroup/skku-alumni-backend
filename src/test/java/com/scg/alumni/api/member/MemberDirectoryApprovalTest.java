package com.scg.alumni.api.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
import com.scg.alumni.domain.member.Member;
import com.scg.alumni.domain.member.MemberRepository;
import com.scg.alumni.domain.member.MemberStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MemberDirectoryApprovalTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired AccessQualificationService access;
    @Autowired EntityManager entityManager;

    @Test
    void activeAndPendingApprovalAndPaymentMatrix() {
        LocalDate date = LocalDate.of(2040, 6, 1);
        long term = createTerm(980, LocalDate.of(2040, 5, 1), LocalDate.of(2041, 4, 30));
        jdbc.update("update users set name = 'Approval Matrix' where id = 1");
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, payment_status) values (1, ?, 1, ?, 'UNPAID')", term, date);
        long history = jdbc.queryForObject("select id from officer_histories where user_id = 1 and officer_term_id = ?", Long.class, term);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'UNPAID')", term);
        for (String status : List.of("ACTIVE", "PENDING")) {
            for (boolean approved : List.of(false, true)) {
                for (String payment : List.of("UNPAID", "PAID")) {
                    jdbc.update("update users set status = ? where id = 1", status);
                    jdbc.update("delete from officer_history_approvals where officer_history_id = ?", history);
                    if (approved) jdbc.update("insert into officer_history_approvals (officer_history_id) values (?)", history);
                    jdbc.update("update payment_records set status = ? where user_id = 1 and officer_term_id = ?", payment, term);
                    entityManager.clear();
                    boolean visible = status.equals("ACTIVE") || approved;
                    assertThat(directory(date)).hasSize(visible ? 1 : 0);
                    assertThat(members.countCurrentPaidDirectory(List.of(-1L), MemberStatus.ACTIVE, date, date)).isEqualTo(visible ? 1 : 0);
                    assertThat(access.levelAt(1, date)).isEqualTo(!visible ? AccessLevel.NONE : payment.equals("PAID") ? AccessLevel.FULL : AccessLevel.LIMITED);
                }
            }
        }
    }

    @Test
    void unapprovedNewerHistoryCannotChangeVisibleRoleOrCursor() {
        LocalDate date = LocalDate.of(2040, 6, 1);
        long approvedTerm = createTerm(982, date.minusMonths(2), date.plusMonths(3));
        long unapprovedTerm = createTerm(983, date.minusMonths(1), date.plusMonths(4));
        jdbc.update("update users set name = 'Approval Matrix', status = 'PENDING' where id = 1");
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, payment_status) values (1, ?, 2, ?, 'UNPAID'), (1, ?, 1, ?, 'UNPAID')", approvedTerm, date.minusMonths(2), unapprovedTerm, date.minusMonths(1));
        jdbc.update("insert into officer_history_approvals (officer_history_id) select id from officer_histories where user_id = 1 and officer_term_id = ?", approvedTerm);
        Member member = directory(date).getFirst();
        assertThat(member.currentOfficerHistory(date).orElseThrow().getOfficerTerm().getId()).isEqualTo(approvedTerm);
        assertThat(members.findDirectoryRoleSortOrders(1L, date, date.minusDays(7), PageRequest.of(0, 1))).containsExactly(20);
    }

    @Test
    void directoryEndsAtTermEndWhileAccessHasSevenDayGrace() {
        LocalDate end = LocalDate.of(2041, 4, 30);
        long term = createTerm(981, end.minusYears(1).plusDays(1), end);
        jdbc.update("update users set name = 'Approval Matrix', status = 'PENDING' where id = 1");
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, ended_at, payment_status) values (1, ?, 1, ?, ?, 'UNPAID')", term, end.minusDays(10), end);
        jdbc.update("insert into officer_history_approvals (officer_history_id) select id from officer_histories where user_id = 1 and officer_term_id = ?", term);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'PAID')", term);
        assertThat(directory(end)).hasSize(1);
        assertThat(access.levelAt(1, end)).isEqualTo(AccessLevel.FULL);
        for (int day : List.of(1, 7)) {
            assertThat(directory(end.plusDays(day))).isEmpty();
            assertThat(access.levelAt(1, end.plusDays(day))).isEqualTo(AccessLevel.FULL);
        }
        assertThat(directory(end.plusDays(8))).isEmpty();
        assertThat(access.levelAt(1, end.plusDays(8))).isEqualTo(AccessLevel.NONE);
    }

    private long createTerm(int generation, LocalDate start, LocalDate end) {
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (?, 1, ?, ?, false)", generation, start, end);
        return jdbc.queryForObject("select id from officer_terms where generation = ?", Long.class, generation);
    }

    private List<Member> directory(LocalDate date) {
        return members.searchCurrentPaidDirectory("%approvalmatrix%", "all", null, null, List.of(-1L), null,
                null, null, null, null, null, null, null, null, null, 1, List.of(-1L),
                MemberStatus.ACTIVE, date, date.minusDays(7), PageRequest.of(0, 50));
    }
}
