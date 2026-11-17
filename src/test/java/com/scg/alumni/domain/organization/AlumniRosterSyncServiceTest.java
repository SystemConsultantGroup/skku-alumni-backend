package com.scg.alumni.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class AlumniRosterSyncServiceTest {
    @Autowired AlumniRosterSyncService sync;
    @Autowired AlumniOrganizationService organizations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void replacesRosterPreservesHistoryAndReuploadIsIdempotent() throws Exception {
        long org = organizations.create("Fictional alumni club", null);
        long formerChair = organizations.addOfficer(org, 1, "Chair", true, null, null);
        organizations.addOfficer(org, 2, "Secretary", false, null, null);
        String name2 = jdbc.queryForObject("select name from users where id = 2", String.class);
        String name3 = jdbc.queryForObject("select name from users where id = 3", String.class);
        byte[] file = workbook(new String[][]{
                {"2", name2, "Deputy", "N", "", "", "", ""},
                {"3", name3, "Chair", "Y", "", "", "", ""},
                {"", "Fictional New Person", "Secretary", "N", "", "", "", ""}
        });
        var preview = sync.preview(org, file);
        assertThat(preview.ending()).hasSize(2);
        var result = sync.apply(org, file, preview.token(), true);
        assertThat(result.ended()).isEqualTo(2);
        assertThat(result.added()).isEqualTo(3);
        assertThat(result.createdUsers()).hasSize(1);
        assertThat(organizations.officers(org, null, 20, true)).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from alumni_organization_officers where id = ? and ended_at_recorded_at is not null", Integer.class, formerChair)).isEqualTo(1);
        var replay = sync.preview(org, file);
        assertThat(replay.alreadyApplied()).isTrue();
        assertThat(sync.apply(org, file, replay.token(), true).unchanged()).isTrue();
        assertThat(organizations.officers(org, null, 20, true)).hasSize(3);
    }

    @Test
    void changedRosterAfterPreviewAbortsReplacement() throws Exception {
        long org = organizations.create("Fictional changing club", null);
        String name1 = jdbc.queryForObject("select name from users where id = 1", String.class);
        byte[] file = workbook(new String[][]{{"1", name1, "Chair", "Y", "", "", "", ""}});
        var preview = sync.preview(org, file);
        organizations.addOfficer(org, 2, "Secretary", false, null, null);
        assertThatThrownBy(() -> sync.apply(org, file, preview.token(), true)).isInstanceOf(ResponseStatusException.class);
        assertThat(organizations.officers(org, null, 20, true)).hasSize(1);
    }

    @Test
    void multiplePresidentsFailBeforeAnyChanges() throws Exception {
        byte[] file = workbook(new String[][]{
                {"1", "Person One", "Chair", "Y", "", "", "", ""},
                {"2", "Person Two", "Chair", "Y", "", "", "", ""}
        });
        assertThatThrownBy(() -> AlumniRosterWorkbook.parse(file)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void matchingPhoneIsFlaggedBeforeAnyAccountIsCreated() throws Exception {
        long org = organizations.create("Fictional candidate club", null);
        String phone = jdbc.queryForObject("select phone from users where id = 1", String.class);
        byte[] file = workbook(new String[][]{{"", "Different Name", "Chair", "Y", "", phone, "", ""}});
        var preview = sync.preview(org, file);
        assertThat(preview.rows().get(0).existingIdentifierConflict()).isTrue();
        assertThatThrownBy(() -> sync.apply(org, file, preview.token(), true)).isInstanceOf(ResponseStatusException.class);
        assertThat(organizations.officers(org, null, 20, true)).isEmpty();
    }

    @Test
    void suppliedIdentifierCannotVerifyAnAccountWithoutThatIdentifier() throws Exception {
        long org = organizations.create("Fictional identity club", null);
        jdbc.update("update users set student_id = null, phone = null where id = 1");
        String name = jdbc.queryForObject("select name from users where id = 1", String.class);
        byte[] studentIdFile = workbook(new String[][]{{"1", name, "Chair", "Y", "12345678", "", "", ""}});
        byte[] phoneFile = workbook(new String[][]{{"1", name, "Chair", "Y", "", "010-1234-5678", "", ""}});

        assertThatThrownBy(() -> sync.preview(org, studentIdFile)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> sync.preview(org, phoneFile)).isInstanceOf(ResponseStatusException.class);
        assertThat(organizations.officers(org, null, 20, true)).isEmpty();
    }

    @Test
    void correctsDatesWithoutReplacingAppointment() throws Exception {
        long org = organizations.create("Fictional dates club", null);
        long officer = organizations.addOfficer(org, 1, "Chair", true, java.time.LocalDate.of(2020, 1, 1), null);
        String name = jdbc.queryForObject("select name from users where id = 1", String.class);
        byte[] file = workbook(new String[][]{{"1", name, "Chair", "Y", "", "", "2021-01-01", "2022-01-01"}});
        var preview = sync.preview(org, file);
        assertThat(preview.rows().getFirst().operation()).isEqualTo("UPDATE_DATES");
        sync.apply(org, file, preview.token(), true);
        assertThat(jdbc.queryForObject("select effective_started_at from alumni_organization_officers where id = ?", java.time.LocalDate.class, officer)).isEqualTo("2021-01-01");
        assertThat(jdbc.queryForObject("select ended_at from alumni_organization_officers where id = ?", java.time.LocalDate.class, officer)).isEqualTo("2022-01-01");
        assertThat(organizations.officers(org, null, 20, true)).isEmpty();
        assertThat(sync.preview(org, file).alreadyApplied()).isTrue();
    }

    @Test
    void shortensExistingEndAndRejectsDatesBeforeStartAtPreview() throws Exception {
        long org = organizations.create("Fictional shortening club", null);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        long officer = organizations.addOfficer(org, 1, "Chair", true, today.minusDays(3), today.plusDays(30));
        String name = jdbc.queryForObject("select name from users where id = 1", String.class);
        byte[] shortened = workbook(new String[][]{{"1", name, "Chair", "Y", "", "", "", today.plusDays(5).toString()}});
        var preview = sync.preview(org, shortened);
        assertThat(preview.rows().getFirst().operation()).isEqualTo("UPDATE_DATES");
        sync.apply(org, shortened, preview.token(), true);
        assertThat(jdbc.queryForObject("select ended_at from alumni_organization_officers where id = ?", java.time.LocalDate.class, officer)).isEqualTo(today.plusDays(5));
        byte[] invalid = workbook(new String[][]{{"1", name, "Chair", "Y", "", "", "", today.minusDays(4).toString()}});
        assertThatThrownBy(() -> sync.preview(org, invalid)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void emptyRosterCannotEndAllOfficers() throws Exception {
        long org = organizations.create("Fictional empty club", null);
        organizations.addOfficer(org, 1, "Chair", true, null, null);
        organizations.addOfficer(org, 2, "Secretary", false, null, null);
        byte[] file = workbook(new String[0][]);
        assertThatThrownBy(() -> sync.preview(org, file)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("빈 명단");
        assertThatThrownBy(() -> sync.apply(org, file, "invalid", true)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("빈 명단");
        assertThat(organizations.officers(org, null, 20, true)).hasSize(2);
    }

    @Test
    void extendsExistingAppointmentWithoutCreatingAnotherHistory() throws Exception {
        long org = organizations.create("Fictional extension club", null);
        java.time.LocalDate initialEnd = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(10);
        java.time.LocalDate extendedEnd = initialEnd.plusDays(30);
        long officer = organizations.addOfficer(org, 1, "Chair", true, null, initialEnd);
        String name = jdbc.queryForObject("select name from users where id = 1", String.class);
        byte[] file = workbook(new String[][]{{"1", name, "Chair", "Y", "", "", "", extendedEnd.toString()}});
        var preview = sync.preview(org, file);
        assertThat(preview.rows().getFirst().operation()).isEqualTo("EXTEND");
        var result = sync.apply(org, file, preview.token(), true);
        assertThat(result.added()).isZero();
        assertThat(result.ended()).isZero();
        assertThat(jdbc.queryForObject("select ended_at from alumni_organization_officers where id = ?", java.time.LocalDate.class, officer)).isEqualTo(extendedEnd);
        assertThat(jdbc.queryForObject("select count(*) from alumni_organization_officers where organization_id = ?", Integer.class, org)).isEqualTo(1);
        assertThat(sync.preview(org, file).alreadyApplied()).isTrue();
    }

    private byte[] workbook(String[][] rows) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet();
            Row header = sheet.createRow(0);
            for (int i = 0; i < AlumniRosterWorkbook.HEADERS.size(); i++) header.createCell(i).setCellValue(AlumniRosterWorkbook.HEADERS.get(i));
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            book.write(output);
            return output.toByteArray();
        }
    }
}
