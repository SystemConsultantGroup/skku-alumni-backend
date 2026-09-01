package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest
class AdminMemberExcelUploadTest {

    private static final List<String> HEADERS =
            List.of("학번", "이름", "학과", "입학연도", "전화번호", "이메일", "회사명", "직위");

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

        Map<String, Object> result = upload(sheetOf(
                List.of("1998310001", "박성균", "법학과", "1998", "", "", "", "")));

        assertThat(result.get("updated")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "select phone, email, company_id, job_title from users where id = 1"))
                .isEqualTo(before);
    }

    @Test
    @Transactional
    void filledCellsStillOverwrite() throws Exception {
        upload(sheetOf(List.of("1998310001", "박성균", "법학과", "1998", "010-9999-0001", "new@example.com", "", "")));

        Map<String, Object> after = jdbcTemplate.queryForMap("select phone, email from users where id = 1");
        assertThat(after.get("phone")).isEqualTo("010-9999-0001");
        assertThat(after.get("email")).isEqualTo("new@example.com");
    }

    @Test
    @Transactional
    void newMemberIsCreatedAsPending() throws Exception {
        Map<String, Object> result = upload(sheetOf(
                List.of("2020999999", "신입생", "법학과", "2020", "010-1234-5678", "new@example.com", "새회사", "사원")));

        assertThat(result.get("created")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from users where student_id = '2020999999'", String.class)).isEqualTo("PENDING");
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
