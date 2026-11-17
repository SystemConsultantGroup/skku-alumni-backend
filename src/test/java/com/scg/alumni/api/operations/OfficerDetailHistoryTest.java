package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.domain.organization.AlumniOrganizationService;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class OfficerDetailHistoryTest {
    @Autowired UserFeatureController controller;
    @Autowired AlumniOrganizationService organizations;
    @Autowired OfficerAssignment assignments;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void idCardUsesAlumnusLabelWhenNoCurrentCentralOffice() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(2L, AuthScope.MEMBER, "Member"), null));
        jdbc.update("update officer_histories set deleted_at = CURRENT_TIMESTAMP where user_id = 2");
        assertThat(controller.findMe().get("officerRoleName")).isEqualTo("성균관대학교 동문");
    }

    @Test
    void currentUnpaidOfficerHasCombinedHistory() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Member"), null));
        long organization = organizations.create("Fictional club", null);
        organizations.addOfficer(organization, 3L, "Secretary", false, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        Map<String, Object> result = controller.findMemberDetail(3L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> histories = (List<Map<String, Object>>) result.get("officerHistories");
        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).get("organizationType")).isEqualTo("GENERAL");
        assertThat(histories.get(1).get("organizationType")).isEqualTo("UNIT");
    }

    @Test
    void unapprovedHistoryIsNotExposedAsCurrentOfficer() {
        jdbc.update("delete from officer_history_approvals where officer_history_id in (select id from officer_histories where user_id = 3)");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(3L, AuthScope.MEMBER, "Member"), null));
        assertThat(controller.findMe().get("officerRoleName")).isEqualTo("성균관대학교 동문");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Member"), null));
        assertThatThrownBy(() -> controller.findMemberDetail(3L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changedRoleIsVisibleAsPreviousRecord() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "Member"), null));
        Long oldRole = jdbc.queryForObject("select officer_role_id from officer_histories where user_id = 3 and officer_term_id = 3", Long.class);
        Long newRole = jdbc.queryForObject("select min(id) from officer_roles where id <> ?", Long.class, oldRole);
        assignments.ensureHistory(3L, 3L, newRole);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> histories = (List<Map<String, Object>>) controller.findMemberDetail(3L).get("officerHistories");
        assertThat(histories).extracting(item -> item.get("recordType")).contains("CURRENT_RECORD", "PREVIOUS_ROLE");
    }
}
