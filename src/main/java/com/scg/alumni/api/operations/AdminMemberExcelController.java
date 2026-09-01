package com.scg.alumni.api.operations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
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

    /**
     * 업로드 양식. 회원 상태는 사무처가 승인 화면에서 다루는 내부 값이라 양식에서 뺐다.
     * 엑셀로 부은 회원은 모두 가입 대기(PENDING)로 들어오고, 승인은 회원 관리에서 한다.
     */
    private static final List<String> UPLOAD_HEADERS = List.of("학번", "이름", "학과", "입학연도", "전화번호", "이메일", "회사명", "직위");

    /** 목록 다운로드. 사무처가 보는 자료라 상태를 포함한다. */
    private static final List<String> DOWNLOAD_HEADERS = List.of("학번", "이름", "학과", "입학연도", "전화번호", "이메일", "회사명", "직위", "상태");
    private static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "REJECTED", "WITHDRAWN");
    private static final int MAX_ROWS = 5_000;

    /** 양식의 예시 행 표식. 학번이 이걸로 시작하면 업로드에서 건너뛰므로, 안 지우고 올려도 회원이 만들어지지 않는다. */
    private static final String EXAMPLE_ROW_MARKER = "예시";

    /** 엑셀에 넣는 사진 칸 크기. 증명사진 비율(3:4)에 맞춘다. */
    private static final int PHOTO_COLUMN_WIDTH = 14 * 256;
    private static final short PHOTO_ROW_HEIGHT_POINTS = 90;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            List<String> majorNames = jdbcTemplate.queryForList("select name from majors where status = 'ACTIVE' order by name", String.class);

            Sheet form = workbook.createSheet("회원 업로드 양식");
            Row header = form.createRow(0);
            for (int index = 0; index < UPLOAD_HEADERS.size(); index++) {
                header.createCell(index).setCellValue(UPLOAD_HEADERS.get(index));
                form.setColumnWidth(index, switch (index) {
                    case 0, 4 -> 18 * 256;
                    case 1, 3 -> 14 * 256;
                    default -> 24 * 256;
                });
            }
            // 학번·전화번호를 숫자 셀로 두면 엑셀이 앞자리 0을 지우거나 지수 표기로 바꾼다. 열 자체를 텍스트로 고정한다.
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            form.setDefaultColumnStyle(0, textStyle);
            form.setDefaultColumnStyle(4, textStyle);

            Font exampleFont = workbook.createFont();
            exampleFont.setItalic(true);
            exampleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle exampleStyle = workbook.createCellStyle();
            exampleStyle.setFont(exampleFont);
            exampleStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            String[] exampleValues = {EXAMPLE_ROW_MARKER + ") 2020123456", "김성균", majorNames.isEmpty() ? "산업공학과" : majorNames.get(0),
                    "2020", "010-1234-5678", "kim@example.com", "성균관대학교", "책임연구원"};
            Row exampleRow = form.createRow(1);
            for (int index = 0; index < exampleValues.length; index++) {
                Cell cell = exampleRow.createCell(index);
                cell.setCellValue(exampleValues[index]);
                cell.setCellStyle(exampleStyle);
            }
            form.createFreezePane(0, 1);

            Sheet guide = workbook.createSheet("작성 안내");
            String[][] guideRows = {
                    {"항목", "작성 방법"},
                    {"예시 행", "첫 시트의 2행은 작성 예시입니다. 지우고 써도 되고, 그대로 두어도 업로드할 때 자동으로 건너뜁니다."},
                    {"컬럼", "첫 행의 컬럼명과 순서는 바꾸거나 지우면 안 됩니다."},
                    {"학번", "필수 · 중복 기준 · 기존 학번이면 그 회원의 정보를 수정합니다. (예: 2020123456)"},
                    {"이름", "필수"},
                    {"학과", "필수 · '학과 목록' 시트에 있는 명칭을 그대로 입력합니다. 목록에 없는 학과를 쓰면 해당 행 번호와 함께 오류가 납니다."},
                    {"입학연도", "필수 · 1900~2100 사이의 4자리 숫자 (예: 2020)"},
                    {"전화번호", "선택 · 010-1234-5678 형식을 권장하며, 하이픈 없이 숫자만 적어도 됩니다."},
                    {"이메일", "선택 · 이메일 주소 형식 (예: kim@example.com)"},
                    {"회사명", "선택 · 없는 회사명은 새로 등록됩니다."},
                    {"직위", "선택 · 회사에서의 직위입니다. (예: 부장, 대표) 동창회 임원 직책을 적는 칸이 아닙니다."},
                    {"임원 직책", "회장·부회장·상임이사·이사 같은 임원 직책과 기수는 엑셀로 올리지 않습니다. 임원 신청을 승인할 때 지정됩니다."},
                    {"사진", "사진은 엑셀에 넣지 않습니다. 파일명을 학번 또는 이름으로 저장한 사진들을 zip으로 묶어 '사진 일괄 등록'으로 올립니다. (예: 2020123456.jpg)"},
                    {"제한", "첫 번째 시트 기준 최대 5,000명, 파일 크기 10MB 이하"},
                    {"참고", "업로드한 회원은 모두 가입 대기 상태로 들어옵니다. 승인은 회원 관리에서 합니다."}
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
            @RequestParam(required = false) String memberStatus,
            @RequestParam(required = false, defaultValue = "false") boolean includePhotos) throws IOException {
        String like = StringUtils.hasText(keyword) ? "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%" : null;
        String status = normalizeOptionalStatus(memberStatus);
        List<Map<String, Object>> members = jdbcTemplate.queryForList("""
                select u.student_id, u.name, m.name as major_name, u.admission_year, u.phone, u.email,
                       co.name as company_name, u.job_title, u.status, u.profile_image_url
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id and co.deleted_at is null
                where u.deleted_at is null
                  and (? is null or lower(u.name) like ? or lower(m.name) like ? or lower(coalesce(co.name, '')) like ?)
                  and (? is null or u.status = ?)
                order by u.id
                """, like, like, like, like, status, status);

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("회원 목록");
            int photoColumn = DOWNLOAD_HEADERS.size();
            Row header = sheet.createRow(0);
            for (int index = 0; index < DOWNLOAD_HEADERS.size(); index++) header.createCell(index).setCellValue(DOWNLOAD_HEADERS.get(index));
            if (includePhotos) header.createCell(photoColumn).setCellValue("사진");

            Drawing<?> drawing = includePhotos ? sheet.createDrawingPatriarch() : null;
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
                if (includePhotos) {
                    row.setHeightInPoints(PHOTO_ROW_HEIGHT_POINTS);
                    drawPhoto(workbook, drawing, member.get("profile_image_url"), photoColumn, row.getRowNum());
                }
            }
            for (int index = 0; index < DOWNLOAD_HEADERS.size(); index++) sheet.autoSizeColumn(index);
            if (includePhotos) sheet.setColumnWidth(photoColumn, PHOTO_COLUMN_WIDTH);
            workbook.write(output);
            return excelResponse(includePhotos ? "members-with-photos.xlsx" : "members.xlsx", output.toByteArray());
        }
    }

    /**
     * 사진만 따로 내려받는다.
     *
     * <p>엑셀에 박힌 사진은 사람과 얼굴을 맞춰보는 용도라 화질이 떨어져도 되지만,
     * 수첩을 인쇄할 업체에는 원본을 줘야 한다. 파일명은 학번과 이름을 붙여
     * 엑셀의 행과 바로 대조할 수 있게 한다.
     */
    @GetMapping("/photos")
    public ResponseEntity<byte[]> downloadPhotos(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String memberStatus) throws IOException {
        String like = StringUtils.hasText(keyword) ? "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%" : null;
        String status = normalizeOptionalStatus(memberStatus);
        List<Map<String, Object>> members = jdbcTemplate.queryForList("""
                select u.student_id, u.name, u.profile_image_url
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id and co.deleted_at is null
                where u.deleted_at is null
                  and u.profile_image_url is not null
                  and (? is null or lower(u.name) like ? or lower(m.name) like ? or lower(coalesce(co.name, '')) like ?)
                  and (? is null or u.status = ?)
                order by u.id
                """, like, like, like, like, status, status);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output)) {
            Set<String> usedNames = new java.util.HashSet<>();
            for (Map<String, Object> member : members) {
                byte[] content = readProfileImage(member.get("profile_image_url"));
                if (content == null) continue;
                String entryName = uniqueEntryName(usedNames, member.get("student_id"), member.get("name"),
                        String.valueOf(member.get("profile_image_url")));
                zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                zip.write(content);
                zip.closeEntry();
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename("member-photos.zip").build());
        return ResponseEntity.ok().headers(headers).body(output.toByteArray());
    }

    private String uniqueEntryName(Set<String> usedNames, Object studentId, Object name, String imageUrl) {
        String extension = imageUrl.substring(imageUrl.lastIndexOf('.'));
        String base = (studentId == null ? "학번없음" : studentId.toString()) + "_" + name;
        String candidate = base + extension;
        int suffix = 2;
        while (!usedNames.add(candidate)) candidate = base + "-" + suffix++ + extension;
        return candidate;
    }

    /** 저장된 프로필 URL에서 오브젝트 이름을 떼어내 스토리지에서 바로 읽는다. */
    private byte[] readProfileImage(Object imageUrl) {
        if (imageUrl == null) return null;
        String url = imageUrl.toString();
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object("profiles/" + fileName)
                .build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            // 사진 한 장 때문에 전체 다운로드가 실패하면 안 된다.
            return null;
        }
    }

    private void drawPhoto(XSSFWorkbook workbook, Drawing<?> drawing, Object imageUrl, int column, int rowNumber) {
        byte[] content = readProfileImage(imageUrl);
        if (content == null) return;
        int pictureType = String.valueOf(imageUrl).toLowerCase(Locale.ROOT).endsWith(".png")
                ? XSSFWorkbook.PICTURE_TYPE_PNG
                : XSSFWorkbook.PICTURE_TYPE_JPEG;
        int pictureIndex = workbook.addPicture(content, pictureType);
        ClientAnchor anchor = workbook.getCreationHelper().createClientAnchor();
        anchor.setCol1(column);
        anchor.setRow1(rowNumber);
        anchor.setCol2(column + 1);
        anchor.setRow2(rowNumber + 1);
        // 셀에 꽉 채운다. 원본 비율이 제각각이라 다소 눌리지만, 이 사진은
        // 이름과 얼굴을 맞춰보는 용도이고 원본은 zip으로 따로 받는다.
        drawing.createPicture(anchor, pictureIndex);
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
                if (value(row, 0, formatter).startsWith(EXAMPLE_ROW_MARKER)) continue;
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
                String phone = normalizePhone(nullable(row, 4, formatter));
                // 기존 회원을 다시 부어도 이미 승인된 상태를 되돌리지 않는다.
                int count = jdbcTemplate.update("""
                        update users set name = ?, major_id = ?, admission_year = ?, phone = ?, email = ?,
                            company_id = ?, job_title = ?, updated_at = CURRENT_TIMESTAMP
                        where student_id = ?
                        """, name, majorId, admissionYear, phone, nullable(row, 5, formatter),
                        companyId, nullable(row, 7, formatter), studentId);
                if (count > 0) {
                    updated++;
                } else {
                    jdbcTemplate.update("""
                            insert into users (student_id, name, password, category, degree, major_id, admission_year,
                                phone, email, company_id, job_title, status, created_at, updated_at)
                            values (?, ?, ?, 'UNDERGRADUATE', 'BACHELOR', ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """, studentId, name, temporaryPassword, majorId, admissionYear, phone,
                            nullable(row, 5, formatter), companyId, nullable(row, 7, formatter));
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
        Long existing = jdbcTemplate.query("select id from companies where deleted_at is null and lower(replace(name, ' ', '')) = lower(replace(?, ' ', '')) limit 1",
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
        for (int index = 0; index < UPLOAD_HEADERS.size(); index++) {
            if (!UPLOAD_HEADERS.get(index).equals(value(row, index, formatter))) {
                throw badRequest("첫 행의 컬럼 순서는 다음과 같아야 합니다: " + String.join(", ", UPLOAD_HEADERS));
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

    /** 엑셀이 숫자 셀의 앞자리 0을 지운 전화번호를 되살린다. (1012345678 → 01012345678) */
    private String normalizePhone(String value) {
        if (value != null && value.matches("1[0-9]{8,9}")) return "0" + value;
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
        for (int index = 0; index < UPLOAD_HEADERS.size(); index++) if (StringUtils.hasText(value(row, index, formatter))) return false;
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
