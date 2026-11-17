package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.time.LocalDate;
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

@SpringBootTest
@Transactional
class OfficerTermManagementServiceTest {
    @Autowired OfficerTermManagementService service;
    @Autowired OfficerAssignment assignment;
    @Autowired AccessQualificationService access;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "관리자"), null));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void aprilSelectionAndMayTransitionPreserveHistoryAndPayments() {
        long next = ((Number) service.create(41, 1, LocalDate.of(2027, 5, 1), LocalDate.of(2028, 4, 30)).get("id")).longValue();
        var march = LocalDate.of(2027, 3, 31);
        var april = LocalDate.of(2027, 4, 1);
        var may = LocalDate.of(2027, 5, 1);
        assertThat(service.options(march)).extracting(OfficerTermManagementService.TermOption::type).containsExactly("CURRENT");
        assertThatThrownBy(() -> service.resolveSelection(next, march)).isInstanceOf(ResponseStatusException.class);
        assertThat(service.options(april)).extracting(OfficerTermManagementService.TermOption::type).containsExactly("CURRENT", "NEXT");
        assertThat(service.resolveSelection(null, april)).isEqualTo(3);
        assertThat(service.resolveSelection(next, april)).isEqualTo(next);
        int oldHistories = jdbc.queryForObject("select count(*) from officer_histories where officer_term_id = 3", Integer.class);
        int oldPayments = jdbc.queryForObject("select count(*) from payment_records where officer_term_id = 3", Integer.class);
        jdbc.update("update payment_records set status = 'PAID' where user_id = 1 and officer_term_id = 3");
        assignment.assign(1L, next, 1L);
        assertThat(access.levelAt(1, april)).isEqualTo(AccessLevel.FULL);
        assertThat(access.levelAt(1, may)).isEqualTo(AccessLevel.LIMITED);
        assertThat(jdbc.queryForObject("select status from payment_records where user_id = 1 and officer_term_id = ?", String.class, next)).isEqualTo("UNPAID");
        assertThat(service.options(may)).extracting(OfficerTermManagementService.TermOption::officerTermId).containsExactly(next);
        assertThat(jdbc.queryForObject("select count(*) from officer_histories where officer_term_id = 3", Integer.class)).isEqualTo(oldHistories);
        assertThat(jdbc.queryForObject("select count(*) from payment_records where officer_term_id = 3", Integer.class)).isEqualTo(oldPayments);
    }

    @Test void rejectsNonAnnualTermsAndDuplicatePeriods() {
        assertThatThrownBy(() -> service.create(41, 3, LocalDate.of(2027, 5, 1), LocalDate.of(2028, 4, 30))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(41, 1, LocalDate.of(2027, 4, 1), LocalDate.of(2028, 4, 30))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(40, 2, LocalDate.of(2026, 5, 1), LocalDate.of(2027, 4, 30))).isInstanceOf(ResponseStatusException.class);
    }
}
