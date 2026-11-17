package com.scg.alumni.domain.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
class CombinedRosterServiceTest {
    @Autowired CombinedRosterService combined;
    @Autowired AlumniOrganizationService organizations;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void auth() { SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "관리자"), null)); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void syncsOnlyNamedOrganizationsAndRollsBackIndependently() throws Exception {
        long first = organizations.create("서울 동문회", null), second = organizations.create("부산동문회", null), untouched = organizations.create("대구동문회", null);
        long old = organizations.addOfficer(first, 1, "기존직책", false, null, null);
        organizations.addOfficer(untouched, 3, "유지직책", false, null, null);
        String one = name(1), two = name(2);
        byte[] file = workbook(new String[][]{{"서울동문회", "1", one, "새직책", "N", "", "", "", ""},
                {"부산 동문회", "1", one, "직책", "N", "", "", "", ""},
                {"부산동문회", "2", two, "회장", "Y", "", "", "", ""}});
        var preview = combined.preview(file);
        assertThat(preview.organizations()).hasSize(2);
        long job = ((Number) combined.apply(file, preview.token(), true).get("jobId")).longValue();
        assertThat(jdbc.queryForObject("select ended_at_recorded_at from alumni_organization_officers where id = ?", java.sql.Timestamp.class, old)).isNotNull();
        assertThat(organizations.officers(untouched, null, 20, true)).hasSize(1);
        assertThat(organizations.officers(second, null, 20, true)).hasSize(2);
        assertThat(combined.rollback(job, List.of(first)).getFirst().get("status")).isEqualTo("ROLLED_BACK");
        assertThat(organizations.officers(first, null, 20, true)).extracting(row -> row.get("title")).containsExactly("기존직책");
        assertThat(organizations.officers(second, null, 20, true)).hasSize(2);
        assertThat(combined.rollback(job, List.of(first)).getFirst().get("status")).isEqualTo("ALREADY_ROLLED_BACK");
        assertThat(combined.rollback(job, List.of(second)).getFirst().get("status")).isEqualTo("ROLLED_BACK");
        assertThat(organizations.officers(second, null, 20, true)).isEmpty();
    }

    @Test void refusesStalePreviewAndDetectsLaterMutationForOneOrganization() throws Exception {
        long first = organizations.create("가상 동문회", null), second = organizations.create("다른 동문회", null);
        byte[] file = workbook(new String[][]{{"가상동문회", "1", name(1), "이사", "N", "", "", "", ""}, {"다른동문회", "2", name(2), "이사", "N", "", "", "", ""}});
        var stale = combined.preview(file);
        organizations.addOfficer(first, 3, "변경", false, null, null);
        assertThatThrownBy(() -> combined.apply(file, stale.token(), true)).isInstanceOf(ResponseStatusException.class);
        var valid = combined.preview(file);
        long job = ((Number) combined.apply(file, valid.token(), true).get("jobId")).longValue();
        organizations.addOfficer(first, 3, "다시변경", false, null, null);
        var result = combined.rollback(job, List.of(first, second));
        assertThat(result).extracting(item -> item.get("status")).containsExactly("CONFLICT", "ROLLED_BACK");
        assertThat(organizations.officers(first, null, 20, true)).hasSize(2);
    }

    @Test void rejectsUnknownAndAmbiguousOrganizationNames() throws Exception {
        organizations.create("이름 중복", null); organizations.create("이름중복", null);
        byte[] unknown = workbook(new String[][]{{"없는동문회", "1", name(1), "이사", "N", "", "", "", ""}});
        byte[] ambiguous = workbook(new String[][]{{"이름중복", "1", name(1), "이사", "N", "", "", "", ""}});
        assertThatThrownBy(() -> combined.preview(unknown)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> combined.preview(ambiguous)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void simultaneousUploadsCannotBothApplyTheSamePreview() throws Exception {
        String orgName = "병행동문회" + java.util.UUID.randomUUID();
        long org = organizations.create(orgName, null);
        byte[] file = workbook(new String[][]{{orgName, "1", name(1), "이사", "N", "", "", "", ""}});
        String token = combined.preview(file).token();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> concurrentApply(file, token, gate));
            var two = pool.submit(() -> concurrentApply(file, token, gate));
            gate.countDown();
            assertThat(List.of(one.get(20, java.util.concurrent.TimeUnit.SECONDS), two.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(organizations.officers(org, null, 20, true)).hasSize(1);
    }

    private boolean concurrentApply(byte[] file, String token, java.util.concurrent.CountDownLatch gate) throws Exception {
        auth();
        try {
            gate.await(); combined.apply(file, token, true); return true;
        } catch (ResponseStatusException conflict) {
            return false;
        } finally { clear(); }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void simultaneousRollbackRestoresOnlyOnce() throws Exception {
        String orgName = "병행복원" + java.util.UUID.randomUUID();
        long org = organizations.create(orgName, null);
        byte[] file = workbook(new String[][]{{orgName, "1", name(1), "이사", "N", "", "", "", ""}});
        long job = ((Number) combined.apply(file, combined.preview(file).token(), true).get("jobId")).longValue();
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentRollback(job, org, gate));
            var second = pool.submit(() -> concurrentRollback(job, org, gate));
            gate.countDown();
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS), second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ROLLED_BACK", "ALREADY_ROLLED_BACK");
        }
        assertThat(organizations.officers(org, null, 20, true)).isEmpty();
    }

    private String concurrentRollback(long job, long org, java.util.concurrent.CountDownLatch gate) throws Exception {
        auth();
        try { gate.await(); return combined.rollback(job, List.of(org)).getFirst().get("status").toString(); }
        finally { clear(); }
    }

    private String name(long id) { return jdbc.queryForObject("select name from users where id = ?", String.class, id); }
    private byte[] workbook(String[][] rows) throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(); var head = sheet.createRow(0);
            for (int i = 0; i < CombinedRosterWorkbook.HEADERS.size(); i++) head.createCell(i).setCellValue(CombinedRosterWorkbook.HEADERS.get(i));
            for (int i = 0; i < rows.length; i++) { var row = sheet.createRow(i + 1); for (int j = 0; j < rows[i].length; j++) row.createCell(j).setCellValue(rows[i][j]); }
            book.write(out); return out.toByteArray();
        }
    }
}
