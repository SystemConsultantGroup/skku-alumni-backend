package com.scg.alumni.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class AlumniOrganizationServiceTest {
    @Autowired AlumniOrganizationService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void presidentMustBeExpiredBeforeReplacementAndHistorySurvives() {
        long organization = service.create("Fictional organization", null);
        long first = service.addOfficer(organization, 1L, "Chair", true, null, null);
        assertThatThrownBy(() -> service.addOfficer(organization, 2L, "Chair", true, null, null))
                .isInstanceOf(ResponseStatusException.class);
        service.expire(organization, first);
        long second = service.addOfficer(organization, 2L, "Chair", true, null, null);
        assertThat(service.officers(organization, null, 20, true)).hasSize(1);
        assertThat(service.officers(organization, null, 20, false)).hasSize(1);
        service.dissolve(organization);
        assertThat(service.officers(organization, null, 20, true)).isEmpty();
        assertThat(service.officers(organization, null, 20, false)).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from alumni_organization_officers where id = ?", Integer.class, second)).isEqualTo(1);
    }

    @Test
    void historicalPresidentDoesNotBlockCurrentPresident() {
        long organization = service.create("Another fictional organization", null);
        service.addOfficer(organization, 1L, "Chair", true, LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        service.addOfficer(organization, 2L, "Chair", true, null, null);
        assertThat(service.officers(organization, null, 20, true)).hasSize(1);
        assertThat(service.officers(organization, null, 20, false)).hasSize(1);
    }

    @Test
    void appointmentWithFutureEndIsCurrentAndCanBeExpiredEarly() {
        long organization = service.create("Fictional future term", null);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        long officer = service.addOfficer(organization, 1L, "Chair", true, null, today.plusDays(10));
        assertThat(service.officers(organization, null, 20, true)).hasSize(1);
        assertThatThrownBy(() -> service.addOfficer(organization, 2L, "Chair", true, null, null))
                .isInstanceOf(ResponseStatusException.class);
        service.expire(organization, officer);
        assertThat(service.officers(organization, null, 20, true)).isEmpty();
        assertThat(service.officers(organization, null, 20, false)).hasSize(1);
    }
}
