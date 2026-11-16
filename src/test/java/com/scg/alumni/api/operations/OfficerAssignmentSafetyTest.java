package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class OfficerAssignmentSafetyTest {
    @Autowired OfficerAssignment assignments;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deletedPaymentIsNotSilentlyRevived() {
        jdbc.update("update payment_records set deleted_at = CURRENT_TIMESTAMP where user_id = 1 and officer_term_id = 3");
        assertThatThrownBy(() -> assignments.ensurePaymentRecord(1L, 3L, 2L))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment_records where user_id = 1 and officer_term_id = 3 and deleted_at is not null", Integer.class)).isEqualTo(1);
    }

    @Test
    void deletedOfficerHistoryIsNotSilentlyRevived() {
        jdbc.update("update officer_histories set deleted_at = CURRENT_TIMESTAMP where user_id = 1 and officer_term_id = 3");
        assertThatThrownBy(() -> assignments.ensureHistory(1L, 3L, 2L))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from officer_histories where user_id = 1 and officer_term_id = 3 and deleted_at is not null", Integer.class)).isEqualTo(1);
    }

    @Test
    void roleChangePreservesPreviousRoleAndPayment() {
        Long oldRole = jdbc.queryForObject("select officer_role_id from officer_histories where user_id = 1 and officer_term_id = 3", Long.class);
        Long newRole = jdbc.queryForObject("select min(id) from officer_roles where id <> ?", Long.class, oldRole);
        String payment = jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = 3", String.class);
        assignments.ensureHistory(1L, 3L, newRole);
        assignments.ensureHistory(1L, 3L, newRole);
        assertThat(jdbc.queryForList("select officer_role_id from officer_role_change_history where user_id = 1 and officer_term_id = 3", Long.class)).containsExactly(oldRole);
        assertThat(jdbc.queryForObject("select officer_role_id from officer_histories where user_id = 1 and officer_term_id = 3", Long.class)).isEqualTo(newRole);
        assertThat(jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = 3", String.class)).isEqualTo(payment);
    }

    @Test
    void repeatedRoleChangesRetainEveryPreviousStoredRoleWithoutInventingDates() {
        Long original = jdbc.queryForObject("select officer_role_id from officer_histories where user_id = 1 and officer_term_id = 3", Long.class);
        var roles = jdbc.queryForList("select id from officer_roles where id <> ? order by id", Long.class, original);
        var start = jdbc.queryForObject("select started_at from officer_histories where user_id = 1 and officer_term_id = 3", java.time.LocalDate.class);
        var end = jdbc.queryForObject("select ended_at from officer_histories where user_id = 1 and officer_term_id = 3", java.time.LocalDate.class);
        assignments.ensureHistory(1L, 3L, roles.get(0));
        assignments.ensureHistory(1L, 3L, roles.get(1));
        var rows = jdbc.queryForList("select officer_role_id, started_at, ended_at from officer_role_change_history where user_id = 1 and officer_term_id = 3 order by id");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> ((Number) row.get("officer_role_id")).longValue()).containsExactly(original, roles.get(0));
        assertThat(rows).allSatisfy(row -> {
            assertThat(((java.sql.Date) row.get("started_at")).toLocalDate()).isEqualTo(start);
            assertThat(((java.sql.Date) row.get("ended_at")).toLocalDate()).isEqualTo(end);
        });
    }

    @Test
    void newHistoryDoesNotMirrorExistingPaidRecord() {
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (990, 1, '2040-05-01', '2041-04-30', false)");
        Long term = jdbc.queryForObject("select max(id) from officer_terms", Long.class);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'PAID')", term);
        assignments.ensureHistory(1L, term, 1L);
        assertThat(jdbc.queryForObject("select payment_status from officer_histories where user_id = 1 and officer_term_id = ?", String.class, term)).isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = ?", String.class, term)).isEqualTo("PAID");
    }

    @Test
    void missingPaymentForLegacyPaidHistoryRequiresReview() {
        jdbc.update("delete from payment_records where user_id = 1 and officer_term_id = 3");
        jdbc.update("update officer_histories set payment_status = 'PAID' where user_id = 1 and officer_term_id = 3");
        assertThatThrownBy(() -> assignments.ensurePaymentRecord(1L, 3L, 1L)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment_records where user_id = 1 and officer_term_id = 3", Integer.class)).isZero();
    }

}
