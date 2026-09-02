package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class AdminMemberExcelUploadTest {

    private static final List<String> HEADERS = List.of(
            "학번", "이름", "킹고아이디", "학과", "입학연도", "졸업연도", "생년월일", "성별",
            "휴대전화", "이메일", "총동창회 직책", "회사명", "직위",
            "자택 우편번호", "자택 주소", "자택 상세주소");

    /** 필수 네 칸만 채운 줄. 나머지는 비워 "건드리지 않음"을 확인한다. */
    private static List<String> row(String studentId, String name, String major, String admissionYear) {
        List<String> cells = new java.util.ArrayList<>(java.util.Collections.nCopies(HEADERS.size(), ""));
        cells.set(0, studentId);
        cells.set(1, name);
        cells.set(3, major);
        cells.set(4, admissionYear);
        return cells;
    }

    private static List<String> with(List<String> cells, int index, String value) {
        List<String> copy = new java.util.ArrayList<>(cells);
        copy.set(index, value);
        return copy;
    }

    @Autowired
    private AdminMemberExcelController adminMemberExcelController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "안상인"), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 이름과 학과만 정리한 명단을 다시 부었을 때, 비워둔 칸 때문에 회원이 앱에서
     * 직접 채운 연락처가 날아가면 안 된다.
     */
    @Test
    @Transactional
    void blankCellsLeaveExistingValuesAlone() throws Exception {
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "select phone, email, company_id, job_title from users where id = 1");
        assertThat(before.get("phone")).isNotNull();

        Map<String, Object> result = upload(sheetOf(row("1998310001", "박성균", "법학과", "1998")));

        assertThat(result.get("updated")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "select phone, email, company_id, job_title from users where id = 1"))
                .isEqualTo(before);
    }

    @Test
    @Transactional
    void filledCellsStillOverwrite() throws Exception {
        upload(sheetOf(with(with(row("1998310001", "박성균", "법학과", "1998"), 8, "010-9999-0001"),
                9, "new@example.com")));

        Map<String, Object> after = jdbcTemplate.queryForMap("select phone, email from users where id = 1");
        assertThat(after.get("phone")).isEqualTo("010-9999-0001");
        assertThat(after.get("email")).isEqualTo("new@example.com");
    }

    @Test
    @Transactional
    void newMemberIsCreatedAsPending() throws Exception {
        Map<String, Object> result = upload(sheetOf(with(with(with(
                row("2020999999", "신입생", "법학과", "2020"), 8, "010-1234-5678"), 11, "새회사"), 12, "사원")));

        assertThat(result.get("created")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from users where student_id = '2020999999'", String.class)).isEqualTo("PENDING");
    }

    @Test
    @Transactional
    void extendedColumnsAreStored() throws Exception {
        List<String> cells = row("2020999998", "확장검증", "법학과", "2020");
        cells.set(2, "skku.new");
        cells.set(5, "2024");
        cells.set(6, "1998.03.12");
        cells.set(7, "남");
        cells.set(13, "03063");
        cells.set(14, "서울특별시 종로구 성균관로 25-2");
        cells.set(15, "101동 1001호");

        upload(sheetOf(cells));

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                select kingo_id, graduation_year, birth_date, gender,
                       home_zipcode, home_address1, home_address2
                from users where student_id = '2020999998'
                """);
        assertThat(stored.get("kingo_id")).isEqualTo("skku.new");
        assertThat(stored.get("graduation_year")).isEqualTo(2024);
        assertThat(stored.get("birth_date")).hasToString("1998-03-12");
        assertThat(stored.get("gender")).isEqualTo("M");
        assertThat(stored.get("home_zipcode")).isEqualTo("03063");
        assertThat(stored.get("home_address2")).isEqualTo("101동 1001호");
    }

    /** 직책을 적으면 현행 임기의 임원 이력과 회비 내역이 함께 만들어져야 한다. */
    @Test
    @Transactional
    void officerRoleCreatesCurrentTermHistoryAndPayment() throws Exception {
        upload(sheetOf(with(row("2020999997", "임원검증", "법학과", "2020"), 10, "이사")));

        Long memberId = jdbcTemplate.queryForObject(
                "select id from users where student_id = '2020999997'", Long.class);
        Map<String, Object> history = jdbcTemplate.queryForMap("""
                select orole.name as role_name, oh.payment_status
                from officer_histories oh
                join officer_terms ot on ot.id = oh.officer_term_id and ot.current_term = true
                join officer_roles orole on orole.id = oh.officer_role_id
                where oh.user_id = ?
                """, memberId);
        assertThat(history.get("role_name")).isEqualTo("이사");
        assertThat(history.get("payment_status")).isEqualTo("UNPAID");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from payment_records pr
                join officer_terms ot on ot.id = pr.officer_term_id and ot.current_term = true
                where pr.user_id = ?
                """, Integer.class, memberId)).isOne();
    }

    /** 이미 납부로 확인된 회원을 명단에 다시 넣어도 미납으로 돌아가면 안 된다. */
    @Test
    @Transactional
    void reuploadKeepsConfirmedPayment() throws Exception {
        upload(sheetOf(with(row("1998310001", "박성균", "법학과", "1998"), 10, "이사")));

        assertThat(jdbcTemplate.queryForObject("""
                select oh.payment_status
                from officer_histories oh
                join officer_terms ot on ot.id = oh.officer_term_id and ot.current_term = true
                where oh.user_id = 1
                """, String.class)).isEqualTo("PAID");
    }

    /**
     * 명단에 처음 보는 학과가 섞여 있다고 업로드 전체를 되돌리면, 사무처는 학과를
     * 손으로 등록하고 처음부터 다시 올려야 한다. 회사명처럼 만들고 넘어간다.
     */
    @Test
    @Transactional
    void unknownMajorIsCreatedAndReported() throws Exception {
        Map<String, Object> result = upload(sheetOf(
                row("2020999993", "새학과", "테스트학부", "2020"),
                row("2020999992", "같은학과", "테스트 학부", "2020")));

        assertThat(result.get("created")).isEqualTo(2);
        // 같은 학과를 표기만 달리 적었다고 학과가 둘로 늘어나면 안 된다.
        assertThat(result.get("createdMajors")).isEqualTo(List.of("테스트학부"));
        Map<String, Object> major = jdbcTemplate.queryForMap(
                "select id, normalized_name, status from majors where name = '테스트학부'");
        assertThat(major.get("normalized_name")).isEqualTo("테스트학부");
        assertThat(major.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForList(
                "select major_id from users where student_id in ('2020999993', '2020999992')", Long.class))
                .containsOnly(((Number) major.get("id")).longValue());
    }

    /** 야간 표기와 공백만 다른 이름은 이미 있는 학과로 붙어야 한다. */
    @Test
    @Transactional
    void nightMarkerVariantReusesTheExistingMajor() throws Exception {
        Long lawId = jdbcTemplate.queryForObject(
                "select id from majors where name = '법학과'", Long.class);

        Map<String, Object> result = upload(sheetOf(row("2020999991", "야간생", "(야) 법학과", "2020")));

        assertThat(result.get("createdMajors")).isEqualTo(List.of());
        assertThat(jdbcTemplate.queryForObject(
                "select major_id from users where student_id = '2020999991'", Long.class)).isEqualTo(lawId);
    }

    @Test
    @Transactional
    void unknownOfficerRoleIsReportedWithTheRowNumber() throws Exception {
        assertThatThrownBy(() -> upload(sheetOf(with(row("2020999996", "오타", "법학과", "2020"), 10, "총재"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("2행: 등록되지 않은 총동창회 직책입니다.");
    }

    @Test
    @Transactional
    void badBirthDateIsReportedWithTheRowNumber() throws Exception {
        assertThatThrownBy(() -> upload(sheetOf(with(row("2020999995", "오타", "법학과", "2020"), 6, "1998년 3월"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("2행: 생년월일은");
    }

    @Test
    @Transactional
    void duplicateKingoIdIsReportedWithTheRowNumber() throws Exception {
        assertThatThrownBy(() -> upload(sheetOf(with(row("2020999994", "중복", "법학과", "2020"), 2, "skku.park"))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("이미 다른 회원이 쓰고 있는 킹고아이디");
    }

    private Map<String, Object> upload(byte[] workbook) {
        MultipartFile file = new MockMultipartFile("file", "members.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook);
        return adminMemberExcelController.upload(file);
    }

    private byte[] sheetOf(List<String>... rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("회원 업로드 양식");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));
            }
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                List<String> values = rows[rowIndex];
                for (int index = 0; index < values.size(); index++) {
                    row.createCell(index).setCellValue(values.get(index));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
