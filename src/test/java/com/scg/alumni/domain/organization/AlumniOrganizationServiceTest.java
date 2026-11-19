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
    void missingTermDatesStayUnknownAfterARecordedClosure() {
        long organization = service.create("Fictional undated organization", null);
        long member = service.addOfficer(organization, 1L, "Old president title", true, null, null);
        jdbc.update("update alumni_organization_officers set created_at = '2020-01-01 03:00:00', updated_at = '2020-01-01 03:00:00' where id = ?", member);

        service.expire(organization, member);

        assertThat(service.officers(organization, null, 20, false)).singleElement().satisfies(record -> {
            assertThat(record.get("title")).isEqualTo("회장");
            assertThat(record.get("known_started_at")).isNull();
            assertThat(record.get("known_ended_at")).isNull();
            assertThat((java.sql.Timestamp) record.get("updated_at"))
                    .isAfter((java.sql.Timestamp) record.get("created_at"));
        });
    }

    @Test
    void legacyPresidencyHasCanonicalReadTitleWithoutChangingTheArchivedRecord() {
        long organization = service.create("Fictional legacy organization", null);
        long member = service.addOfficer(organization, 1L, "회장", true, null, null);
        jdbc.update("update alumni_organization_officers set title = '동문회장' where id = ?", member);

        assertThat(service.officers(organization, null, 20, true)).singleElement()
                .satisfies(record -> assertThat(record.get("title")).isEqualTo("회장"));
        assertThat(jdbc.queryForObject("select title from alumni_organization_officers where id = ?", String.class, member))
                .isEqualTo("동문회장");
    }

    @Test
    void canRecordOnlyAKnownEndDateInThePast() {
        long organization = service.create("Fictional historical organization", null);
        LocalDate knownEnd = LocalDate.of(2018, 12, 31);
        service.addOfficer(organization, 1L, "총무", false, null, knownEnd);

        assertThat(service.officers(organization, null, 20, false)).singleElement().satisfies(record -> {
            assertThat(record.get("known_started_at")).isNull();
            assertThat(record.get("known_ended_at").toString()).isEqualTo(knownEnd.toString());
        });
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
