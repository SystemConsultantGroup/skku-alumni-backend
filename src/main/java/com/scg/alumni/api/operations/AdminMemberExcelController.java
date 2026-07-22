package com.scg.alumni.api.operations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/excel")
public class AdminMemberExcelController {

    private static final List<String> HEADERS = List.of("학번", "이름", "학과", "입학연도", "전화번호", "이메일", "회사명", "직위", "상태");
    private static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "REJECTED");
    private static final int MAX_ROWS = 5_000;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet form = workbook.createSheet("회원 업로드 양식");
            Row header = form.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) {
                header.createCell(index).setCellValue(HEADERS.get(index));
                form.setColumnWidth(index, switch (index) {
                    case 0, 4 -> 18 * 256;
                    case 1, 3, 8 -> 14 * 256;
                    default -> 24 * 256;
                });
            }
            form.createFreezePane(0, 1);

            Sheet guide = workbook.createSheet("작성 안내");
            String[][] guideRows = {
                    {"항목", "작성 방법"},
                    {"학번", "필수 · 중복 기준 · 기존 학번이면 회원 정보를 수정합니다."},
                    {"이름", "필수"},
                    {"학과", "필수 · '학과 목록' 시트에 있는 명칭을 정확히 입력합니다."},
                    {"입학연도", "필수 · 1900~2100 사이의 4자리 숫자"},
                    {"전화번호", "선택"},
                    {"이메일", "선택"},
                    {"회사명", "선택 · 없는 회사명은 새로 등록됩니다."},
                    {"직위", "선택"},
                    {"상태", "선택 · PENDING, ACTIVE, REJECTED 중 하나 · 비워두면 PENDING"},
                    {"제한", "첫 번째 시트 기준 최대 5,000명, 파일 크기 10MB 이하"}
            };
            for (int rowIndex = 0; rowIndex < guideRows.length; rowIndex++) {
                Row row = guide.createRow(rowIndex);
                row.createCell(0).setCellValue(guideRows[rowIndex][0]);
                row.createCell(1).setCellValue(guideRows[rowIndex][1]);
            }
            guide.setColumnWidth(0, 18 * 256);
            guide.setColumnWidth(1, 70 * 256);
            guide.createFreezePane(0, 1);

            Sheet majors = workbook.createSheet("학과 목록");
            majors.createRow(0).createCell(0).setCellValue("사용 가능한 학과명");
            List<String> majorNames = jdbcTemplate.queryForList("select name from majors where status = 'ACTIVE' order by name", String.class);
            for (int index = 0; index < majorNames.size(); index++) majors.createRow(index + 1).createCell(0).setCellValue(majorNames.get(index));
            majors.setColumnWidth(0, 36 * 256);
            majors.createFreezePane(0, 1);

            workbook.write(output);
            return excelResponse("member-upload-template.xlsx", output.toByteArray());
        }
    }

    @GetMapping
    public ResponseEntity<byte[]> download(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String memberStatus) throws IOException {
        String like = StringUtils.hasText(keyword) ? "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%" : null;
        String status = normalizeOptionalStatus(memberStatus);
        List<Map<String, Object>> members = jdbcTemplate.queryForList("""
                select u.student_id, u.name, m.name as major_name, u.admission_year, u.phone, u.email,
                       co.name as company_name, u.job_title, u.status
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                where (? is null or lower(u.name) like ? or lower(m.name) like ? or lower(coalesce(co.name, '')) like ?)
                  and (? is null or u.status = ?)
                order by u.id
                """, like, like, like, like, status, status);

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("회원 목록");
            Row header = sheet.createRow(0);
            for (int index = 0; index < HEADERS.size(); index++) header.createCell(index).setCellValue(HEADERS.get(index));
            int rowIndex = 1;
            for (Map<String, Object> member : members) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, member.get("student_id"));
                write(row, 1, member.get("name"));
                write(row, 2, member.get("major_name"));
                write(row, 3, member.get("admission_year"));
                write(row, 4, member.get("phone"));
                write(row, 5, member.get("email"));
                write(row, 6, member.get("company_name"));
                write(row, 7, member.get("job_title"));
                write(row, 8, member.get("status"));
            }
            for (int index = 0; index < HEADERS.size(); index++) sheet.autoSizeColumn(index);
            workbook.write(output);
            return excelResponse("members.xlsx", output.toByteArray());
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) throw badRequest("업로드할 엑셀 파일을 선택해주세요.");
        if (file.getSize() > 10 * 1024 * 1024) throw badRequest("엑셀 파일은 10MB 이하만 업로드할 수 있습니다.");
        int created = 0;
        int updated = 0;
        String temporaryPassword = passwordEncoder.encode(UUID.randomUUID().toString());
        DataFormatter formatter = new DataFormatter(Locale.KOREA);
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) throw badRequest("첫 번째 시트에 회원 목록이 없습니다.");
            validateHeaders(sheet.getRow(0), formatter);
            if (sheet.getLastRowNum() > MAX_ROWS) throw badRequest("한 번에 최대 5,000명까지 업로드할 수 있습니다.");
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || isBlank(row, formatter)) continue;
                int excelRow = index + 1;
                String studentId = required(row, 0, "학번", excelRow, formatter);
                String name = required(row, 1, "이름", excelRow, formatter);
                String majorName = required(row, 2, "학과", excelRow, formatter);
                int admissionYear = parseYear(required(row, 3, "입학연도", excelRow, formatter), excelRow);
                Long majorId = jdbcTemplate.query("select id from majors where lower(replace(name, ' ', '')) = lower(replace(?, ' ', '')) limit 1",
                        resultSet -> resultSet.next() ? resultSet.getLong(1) : null, majorName);
                if (majorId == null) throw badRequest(excelRow + "행: 등록되지 않은 학과입니다. (" + majorName + ")");
                String companyName = value(row, 6, formatter);
                Long companyId = StringUtils.hasText(companyName) ? findOrCreateCompany(companyName.trim()) : null;
                String status = value(row, 8, formatter).toUpperCase(Locale.ROOT);
                if (!StringUtils.hasText(status)) status = "PENDING";
                if (!STATUSES.contains(status)) throw badRequest(excelRow + "행: 상태는 PENDING, ACTIVE, REJECTED 중 하나여야 합니다.");
                int count = jdbcTemplate.update("""
                        update users set name = ?, major_id = ?, admission_year = ?, phone = ?, email = ?,
                            company_id = ?, job_title = ?, status = ?, updated_at = CURRENT_TIMESTAMP
                        where student_id = ?
                        """, name, majorId, admissionYear, nullable(row, 4, formatter), nullable(row, 5, formatter),
                        companyId, nullable(row, 7, formatter), status, studentId);
                if (count > 0) {
                    updated++;
                } else {
                    jdbcTemplate.update("""
                            insert into users (student_id, name, password, category, degree, major_id, admission_year,
                                phone, email, company_id, job_title, status, created_at, updated_at)
                            values (?, ?, ?, 'UNDERGRADUATE', 'BACHELOR', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """, studentId, name, temporaryPassword, majorId, admissionYear, nullable(row, 4, formatter),
                            nullable(row, 5, formatter), companyId, nullable(row, 7, formatter), status);
                    created++;
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("엑셀 파일을 읽을 수 없습니다. .xlsx 형식과 입력값을 확인해주세요.");
        }
        return Map.of("created", created, "updated", updated, "total", created + updated);
    }

    private Long findOrCreateCompany(String name) {
        Long existing = jdbcTemplate.query("select id from companies where lower(replace(name, ' ', '')) = lower(replace(?, ' ', '')) limit 1",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : null, name);
        if (existing != null) return existing;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("insert into companies (name, created_at, updated_at) values (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", new String[]{"id"});
            statement.setString(1, name);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void validateHeaders(Row row, DataFormatter formatter) {
        if (row == null) throw badRequest("첫 행에 컬럼명이 필요합니다.");
        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(value(row, index, formatter))) {
                throw badRequest("첫 행의 컬럼 순서는 다음과 같아야 합니다: " + String.join(", ", HEADERS));
            }
        }
    }

    private int parseYear(String value, int row) {
        try {
            int year = Integer.parseInt(value.replace(".0", ""));
            if (year < 1900 || year > 2100) throw new NumberFormatException();
            return year;
        } catch (NumberFormatException exception) {
            throw badRequest(row + "행: 입학연도는 1900~2100 사이 숫자여야 합니다.");
        }
    }

    private String normalizeOptionalStatus(String value) {
        if (!StringUtils.hasText(value) || "all".equalsIgnoreCase(value)) return null;
        String status = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) throw badRequest("올바르지 않은 회원 상태입니다.");
        return status;
    }

    private String required(Row row, int cell, String label, int rowNumber, DataFormatter formatter) {
        String value = value(row, cell, formatter);
        if (!StringUtils.hasText(value)) throw badRequest(rowNumber + "행: " + label + "은(는) 필수입니다.");
        return value;
    }

    private String nullable(Row row, int cell, DataFormatter formatter) {
        String value = value(row, cell, formatter);
        return StringUtils.hasText(value) ? value : null;
    }

    private String value(Row row, int index, DataFormatter formatter) {
        Cell cell = row == null ? null : row.getCell(index);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private boolean isBlank(Row row, DataFormatter formatter) {
        for (int index = 0; index < HEADERS.size(); index++) if (StringUtils.hasText(value(row, index, formatter))) return false;
        return true;
    }

    private void write(Row row, int index, Object value) {
        if (value instanceof Number number) row.createCell(index).setCellValue(number.doubleValue());
        else row.createCell(index).setCellValue(value == null ? "" : value.toString());
    }

    private ResponseEntity<byte[]> excelResponse(String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }
}
