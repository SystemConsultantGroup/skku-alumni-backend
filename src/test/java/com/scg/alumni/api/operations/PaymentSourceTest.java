package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PaymentSourceTest {
    @Autowired AdminFeatureController admin;
    @Autowired UserFeatureController user;
    @Autowired AdminMemberRelationController relations;
    @Autowired AdminPaymentTransitionController transition;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void paymentUpdatesPreserveLegacyValueAndPaymentDate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "Admin"), null));
        long payment = jdbc.queryForObject("select id from payment_records where user_id = 1 and officer_term_id = 3", Long.class);
        jdbc.update("update officer_histories set payment_status = 'PAID' where user_id = 1 and officer_term_id = 3");
        jdbc.update("update payment_records set status = 'PAID', paid_at = '2026-05-10 12:00:00', amount = 12345 where id = ?", payment);
        admin.updatePaymentStatus(payment, new AdminFeatureController.StatusUpdateRequest("UNPAID"));
        assertThat(jdbc.queryForObject("select payment_status from officer_histories where user_id = 1 and officer_term_id = 3", String.class)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select paid_at from payment_records where id = ?", java.time.LocalDateTime.class, payment)).isEqualTo(java.time.LocalDateTime.of(2026, 5, 10, 12, 0));
        assertThat(jdbc.queryForObject("select amount from payment_records where id = ?", Integer.class, payment)).isEqualTo(12345);
        relations.removePayment(1L, payment);
        assertThat(jdbc.queryForObject("select count(*) from payment_records where id = ? and deleted_at is not null and amount = 12345 and paid_at is not null", Integer.class, payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payment_status from officer_histories where user_id = 1 and officer_term_id = 3", String.class)).isEqualTo("PAID");
    }

    @Test
    void paymentListsIncludeOrphanRecordsButExcludeDeletedRecords() {
        Long paymentId = jdbc.queryForObject("select id from payment_records where user_id = 1 and officer_term_id = 3", Long.class);
        jdbc.update("update officer_histories set deleted_at = CURRENT_TIMESTAMP where user_id = 1 and officer_term_id = 3");
        var page = admin.findPayments(null, null, null, null, null, null, null, null, 1, 100);
        assertThat(page.items()).extracting(row -> ((Number) row.get("id")).longValue()).contains(paymentId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Member"), null));
        @SuppressWarnings("unchecked") List<Map<String, Object>> payments = (List<Map<String, Object>>) user.findMyPayment().get("items");
        assertThat(payments).extracting(row -> ((Number) row.get("id")).longValue()).contains(paymentId);
        jdbc.update("update payment_records set deleted_at = CURRENT_TIMESTAMP where id = ?", paymentId);
        assertThat(admin.findPayments(null, null, null, null, null, null, null, null, 1, 100).items())
                .extracting(row -> ((Number) row.get("id")).longValue()).doesNotContain(paymentId);
    }

    @Test
    void editingOfficerHistoryCanUseTheAuthoritativePaymentWithoutRepeatingIt() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "Admin"), null));
        jdbc.update("update payment_records set status = 'PAID' where user_id = 1 and officer_term_id = 3");
        Long historyId = jdbc.queryForObject("select id from officer_histories where user_id = 1 and officer_term_id = 3", Long.class);
        Long roleId = jdbc.queryForObject("select officer_role_id from officer_histories where id = ?", Long.class, historyId);
        java.time.LocalDate start = jdbc.queryForObject("select started_at from officer_histories where id = ?", java.time.LocalDate.class, historyId);
        var result = relations.updateOfficerHistory(1L, historyId,
                new AdminMemberRelationController.OfficerHistoryUpdateRequest(roleId, start, null, null));
        assertThat(result.get("paymentStatus")).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = 3", String.class)).isEqualTo("PAID");
    }

    @Test
    void reconciliationListsMismatchesAndPaymentsWithoutHistory() {
        jdbc.update("update payment_records set status = 'PAID' where user_id = 1 and officer_term_id = 3");
        jdbc.update("update officer_histories set payment_status = 'UNPAID' where user_id = 1 and officer_term_id = 3");
        assertThat(transition.histories(null, 100).items())
                .anySatisfy(row -> {
                    assertThat(((Number) row.get("userId")).longValue()).isEqualTo(1L);
                    assertThat(row.get("historyStatus")).isEqualTo("UNPAID");
                    assertThat(row.get("paymentStatus")).isEqualTo("PAID");
                });
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (991, 1, '2040-05-01', '2041-04-30', false)");
        long termId = jdbc.queryForObject("select max(id) from officer_terms", Long.class);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'PAID')", termId);
        assertThat(transition.orphanPayments(null, 100).items())
                .anySatisfy(row -> assertThat(((Number) row.get("officerTermId")).longValue()).isEqualTo(termId));
    }

    @Test
    void explicitRoleChangePreservesDatedPeriodAndPayment() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "Admin"), null));
        long historyId = jdbc.queryForObject("select id from officer_histories where user_id = 1 and officer_term_id = 3", Long.class);
        long oldRole = jdbc.queryForObject("select officer_role_id from officer_histories where id = ?", Long.class, historyId);
        long newRole = jdbc.queryForObject("select min(id) from officer_roles where id <> ?", Long.class, oldRole);
        var start = jdbc.queryForObject("select started_at from officer_histories where id = ?", java.time.LocalDate.class, historyId);
        var effective = start.plusDays(1);
        String status = jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = 3", String.class);
        relations.changeOfficerRole(1L, historyId, new AdminMemberRelationController.OfficerRoleChangeRequest(newRole, effective));
        assertThat(jdbc.queryForObject("select ended_at from officer_role_change_history where officer_history_id = ?", java.time.LocalDate.class, historyId)).isEqualTo(start);
        assertThat(jdbc.queryForObject("select started_at from officer_histories where id = ?", java.time.LocalDate.class, historyId)).isEqualTo(effective);
        assertThat(jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = 3", String.class)).isEqualTo(status);
        long previousId = jdbc.queryForObject("select id from officer_role_change_history where officer_history_id = ?", Long.class, historyId);
        relations.updatePreviousOfficerRole(1L, previousId,
                new AdminMemberRelationController.PreviousOfficerRoleUpdateRequest(oldRole, start, start));
        assertThat(jdbc.queryForObject("select ended_at from officer_role_change_history where id = ?", java.time.LocalDate.class, previousId)).isEqualTo(start);
    }
}
