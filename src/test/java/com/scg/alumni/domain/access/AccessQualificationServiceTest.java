package com.scg.alumni.domain.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AccessQualificationServiceTest {
    @Autowired AccessQualificationService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void newUnpaidTermWinsOverPaidGraceAndOtherQualifications() {
        jdbc.update("update users set status = 'PENDING' where id = 1");
        jdbc.update("insert into vip_qualifications (user_id) values (1)");
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (900, 1, '2040-05-01', '2041-04-30', false)");
        long oldTerm = jdbc.queryForObject("select max(id) from officer_terms", Long.class);
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, payment_status) values (1, ?, 1, '2040-05-01', 'PAID')", oldTerm);
        approve(1, oldTerm);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'PAID')", oldTerm);
        LocalDate transition = LocalDate.of(2041, 5, 1);
        assertThat(service.levelOn(1, transition)).isEqualTo(AccessLevel.FULL);
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (900, 2, '2041-05-01', '2042-04-30', false)");
        long newTerm = jdbc.queryForObject("select max(id) from officer_terms", Long.class);
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, payment_status) values (1, ?, 1, '2041-05-01', 'UNPAID')", newTerm);
        approve(1, newTerm);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'UNPAID')", newTerm);
        assertThat(service.levelOn(1, transition)).isEqualTo(AccessLevel.LIMITED);
        jdbc.update("update users set status = 'WITHDRAWN' where id = 1");
        assertThat(service.levelOn(1, transition)).isEqualTo(AccessLevel.NONE);
    }

    @Test
    void vipCanRemainAfterOfficerGraceEnds() {
        jdbc.update("update users set status = 'PENDING' where id = 1");
        jdbc.update("insert into vip_qualifications (user_id) values (1)");
        assertThat(service.levelOn(1, LocalDate.of(2099, 5, 8))).isEqualTo(AccessLevel.LIMITED);
        jdbc.update("update users set account_blocked = true where id = 1");
        assertThat(service.levelOn(1, LocalDate.of(2099, 5, 8))).isEqualTo(AccessLevel.NONE);
        jdbc.update("update users set account_blocked = false where id = 1");
        jdbc.update("update vip_qualifications set revoked_at = CURRENT_TIMESTAMP where user_id = 1");
        assertThat(service.levelOn(1, LocalDate.of(2099, 5, 8))).isEqualTo(AccessLevel.NONE);
    }

    @Test
    void paidTermGrantsExactlySevenDaysOfGrace() {
        jdbc.update("update users set status = 'PENDING' where id = 1");
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (901, 1, '2040-05-01', '2041-04-30', false)");
        long term = jdbc.queryForObject("select max(id) from officer_terms", Long.class);
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, ended_at, payment_status) values (1, ?, 1, '2040-05-01', '2041-04-30', 'PAID')", term);
        approve(1, term);
        jdbc.update("insert into payment_records (user_id, officer_term_id, amount, status) values (1, ?, 100, 'PAID')", term);
        assertThat(service.levelOn(1, LocalDate.of(2041, 5, 7))).isEqualTo(AccessLevel.FULL);
        assertThat(service.levelOn(1, LocalDate.of(2041, 5, 8))).isEqualTo(AccessLevel.NONE);
    }

    @Test
    void pendingLegacyHistoryIsNotAnApprovalButExplicitApprovalGrantsAccess() {
        jdbc.update("update users set status = 'PENDING' where id = 1");
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (902, 1, '2040-05-01', '2041-04-30', false)");
        long term = jdbc.queryForObject("select max(id) from officer_terms", Long.class);
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at, payment_status) values (1, ?, 1, '2040-05-01', 'UNPAID')", term);
        assertThat(service.levelOn(1, LocalDate.of(2040, 6, 1))).isEqualTo(AccessLevel.NONE);
        approve(1, term);
        assertThat(service.levelOn(1, LocalDate.of(2040, 6, 1))).isEqualTo(AccessLevel.LIMITED);
    }

    private void approve(long userId, long termId) {
        jdbc.update("insert into officer_history_approvals (officer_history_id) select id from officer_histories where user_id = ? and officer_term_id = ?", userId, termId);
    }

}
