package com.scg.alumni.api.operations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import com.scg.alumni.domain.academic.MajorNames;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
     * 업로드 양식.
     *
     * <p>회원 상태는 사무처가 승인 화면에서 다루는 내부 값이라 양식에서 뺐다.
     * 엑셀로 부은 회원은 모두 가입 대기(PENDING)로 들어오고, 승인은 회원 관리에서 한다.
     *
     * <p>공개 범위와 알림 설정도 뺐다. 본인이 앱에서 정하는 값이라, 사무처가 일괄로
     * 켜면 회원이 감춰둔 연락처를 남이 공개로 뒤집는 셈이 된다.
     *
     * <p>사진은 파일이라 엑셀에 담지 않고 zip 일괄 등록으로 받는다.
     */
    private static final List<String> UPLOAD_HEADERS = List.of(
            "학번", "이름", "킹고아이디", "학과", "입학연도", "졸업연도", "생년월일", "성별",
            "휴대전화", "이메일", "총동창회 직책", "회사명", "직위",
            "자택 우편번호", "자택 주소", "자택 상세주소");

    /** 목록 다운로드. 사무처가 보는 자료라 상태를 포함한다. 나머지는 업로드 양식과 같아야 왕복이 된다. */
    private static final List<String> DOWNLOAD_HEADERS = concat(UPLOAD_HEADERS, "상태");

    private static List<String> concat(List<String> headers, String extra) {
        List<String> merged = new java.util.ArrayList<>(headers);
        merged.add(extra);
        return List.copyOf(merged);
    }

    /** 각 칸의 자리. 숫자로 세면 칸을 하나 끼울 때마다 모든 인덱스가 밀린다. */
    private static final int STUDENT_ID = 0;
    private static final int NAME = 1;
    private static final int KINGO_ID = 2;
    private static final int MAJOR = 3;
    private static final int ADMISSION_YEAR = 4;
    private static final int GRADUATION_YEAR = 5;
    private static final int BIRTH_DATE = 6;
    private static final int GENDER = 7;
    private static final int PHONE = 8;
    private static final int EMAIL = 9;
    private static final int OFFICER_ROLE = 10;
    private static final int COMPANY = 11;
    private static final int JOB_TITLE = 12;
    private static final int HOME_ZIPCODE = 13;
    private static final int HOME_ADDRESS1 = 14;
    private static final int HOME_ADDRESS2 = 15;
    private static final Set<String> STATUSES = Set.of("PENDING", "ACTIVE", "REJECTED", "WITHDRAWN");
    private static final int MAX_ROWS = 5_000;

    /** 양식의 예시 행 표식. 학번이 이걸로 시작하면 업로드에서 건너뛰므로, 안 지우고 올려도 회원이 만들어지지 않는다. */
    private static final String EXAMPLE_ROW_MARKER = "예시";

    /** 엑셀에 넣는 사진 칸 크기. 증명사진 비율(3:4)에 맞춘다. */
    private static final int PHOTO_COLUMN_WIDTH = 14 * 256;
    private static final short PHOTO_ROW_HEIGHT_POINTS = 90;

    private final JdbcTemplate jdbcTemplate;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final OfficerAssignment officerAssignment;

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            List<String> majorNames = jdbcTemplate.queryForList("select name from majors where status = 'ACTIVE' order by name", String.class);
            List<String> roleNames = jdbcTemplate.queryForList("select name from officer_roles order by sort_order, id", String.class);

            Sheet form = workbook.createSheet("회원 업로드 양식");
            Row header = form.createRow(0);
            for (int index = 0; index < UPLOAD_HEADERS.size(); index++) {
                header.createCell(index).setCellValue(UPLOAD_HEADERS.get(index));
                form.setColumnWidth(index, switch (index) {
                    case STUDENT_ID, PHONE, BIRTH_DATE, HOME_ADDRESS1 -> 18 * 256;
                    case NAME, GENDER, ADMISSION_YEAR, GRADUATION_YEAR -> 12 * 256;
                    default -> 20 * 256;
                });
            }
            // 학번·휴대전화·우편번호를 숫자 셀로 두면 엑셀이 앞자리 0을 지우거나 지수 표기로
            // 바꾼다. 생년월일은 날짜로 바꿔 서식이 제각각이 된다. 열 자체를 텍스트로 고정한다.
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int index : new int[]{STUDENT_ID, PHONE, BIRTH_DATE, HOME_ZIPCODE}) {
                form.setDefaultColumnStyle(index, textStyle);
            }

            Font exampleFont = workbook.createFont();
            exampleFont.setItalic(true);
            exampleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle exampleStyle = workbook.createCellStyle();
            exampleStyle.setFont(exampleFont);
            exampleStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            String[] exampleValues = {EXAMPLE_ROW_MARKER + ") 2020123456", "김성균", "skku.kim",
                    majorNames.isEmpty() ? "산업공학과" : majorNames.get(0), "2020", "2024",
                    "1998-03-12", "남", "010-1234-5678", "kim@example.com", roleNames.isEmpty() ? "이사" : roleNames.get(0),
                    "성균관대학교", "책임연구원", "03063", "서울특별시 종로구 성균관로 25-2", "101동 1001호"};
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
                    {"빈 칸", "비워두면 그 항목은 건드리지 않습니다. 기존 회원의 값이 지워지지 않으니, 고칠 항목만 채워 올려도 됩니다. 반대로 엑셀로 값을 지울 수는 없습니다 — 지우는 일은 회원 상세 화면에서 합니다."},
                    {"학번", "필수 · 중복 기준 · 기존 학번이면 그 회원의 정보를 수정합니다. (예: 2020123456)"},
                    {"이름", "필수"},
                    {"킹고아이디", "선택 · 학교 포털 아이디를 적어두는 참고 칸입니다. 앱 로그인 아이디가 아닙니다 — 아이디와 비밀번호는 회원이 앱에서 직접 정합니다. 회원마다 달라야 하며, 다른 회원이 쓰고 있으면 해당 행 번호와 함께 오류가 납니다."},
                    {"학과", "필수 · '학과 목록' 시트에 있는 명칭을 그대로 입력합니다. 목록에 없는 학과는 새로 등록되며, 어떤 학과가 등록됐는지 업로드 뒤에 알려드립니다. 오타도 그대로 등록되니 확인해주세요."},
                    {"입학연도", "필수 · 1900~2100 사이의 4자리 숫자 (예: 2020)"},
                    {"졸업연도", "선택 · 1900~2100 사이의 4자리 숫자 (예: 2024)"},
                    {"생년월일", "선택 · 1998-03-12 처럼 적습니다. 1998.03.12 / 1998/03/12 / 19980312 도 됩니다."},
                    {"성별", "선택 · 남 또는 여 (M, F 도 됩니다)"},
                    {"휴대전화", "선택 · 010-1234-5678 형식을 권장하며, 하이픈 없이 숫자만 적어도 됩니다."},
                    {"이메일", "선택 · 이메일 주소 형식 (예: kim@example.com)"},
                    {"총동창회 직책", "선택 · '직책 목록' 시트에 있는 명칭을 그대로 입력합니다. 적으면 현행 임기의 임원 이력과 회비 내역(미납)이 함께 만들어집니다. 이미 납부로 확인된 회원의 납부 여부는 바뀌지 않습니다."},
                    {"회사명", "선택 · 없는 회사명은 새로 등록됩니다."},
                    {"직위", "선택 · 회사에서의 직위입니다. (예: 부장, 대표) 총동창회 직책과는 다른 칸입니다."},
                    {"자택 우편번호", "선택 · 5자리 (예: 03063)"},
                    {"자택 주소 / 상세주소", "선택 · 도로명 주소와 동·호수를 나눠 적습니다."},
                    {"사진", "사진은 엑셀에 넣지 않습니다. 파일명을 학번 또는 이름으로 저장한 사진들을 zip으로 묶어 '사진 일괄 등록'으로 올립니다. (예: 2020123456.jpg)"},
                    {"공개 범위·알림", "연락처 공개 여부와 알림 설정은 엑셀로 올리지 않습니다. 회원이 앱에서 직접 정하는 값이라 일괄로 바꾸지 않습니다."},
                    {"제한", "첫 번째 시트 기준 최대 5,000명, 파일 크기 10MB 이하"},
                    {"참고", "새로 등록되는 회원은 모두 가입 대기 상태로 들어옵니다. 승인은 회원 관리에서 하며, 이미 승인된 회원의 상태는 바뀌지 않습니다."},
                    {"계정", "엑셀로는 아이디와 비밀번호를 만들 수 없습니다. 회원이 앱의 '계정 만들기'에서 본인 확인 후 직접 정합니다. 아직 정하지 않은 회원은 회원 관리의 '계정 미등록'에서 모아볼 수 있습니다."}
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

            Sheet roles = workbook.createSheet("직책 목록");
            roles.createRow(0).createCell(0).setCellValue("사용 가능한 총동창회 직책");
            for (int index = 0; index < roleNames.size(); index++) roles.createRow(index + 1).createCell(0).setCellValue(roleNames.get(index));
            roles.setColumnWidth(0, 36 * 256);
            roles.createFreezePane(0, 1);

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
        // 내려받은 파일을 그대로 고쳐 다시 올릴 수 있어야 해서 업로드 양식과 같은 칸을 담는다.
        // 임원 직책은 현행 임기 기준이다. 지난 임기 이력까지 담으면 한 줄에 여러 값이 생긴다.
        List<Map<String, Object>> members = jdbcTemplate.queryForList("""
                select u.student_id, u.name, u.kingo_id, m.name as major_name,
                       u.admission_year, u.graduation_year, u.birth_date, u.gender,
                       u.phone, u.email, orole.name as officer_role_name,
                       co.name as company_name, u.job_title,
                       u.home_zipcode, u.home_address1, u.home_address2,
                       u.status, u.profile_image_url
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id and co.deleted_at is null
                left join officer_terms ot on ot.current_term = true
                left join officer_histories oh on oh.user_id = u.id and oh.officer_term_id = ot.id
                    and oh.deleted_at is null
                left join officer_roles orole on orole.id = oh.officer_role_id
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
                write(row, STUDENT_ID, member.get("student_id"));
                write(row, NAME, member.get("name"));
                write(row, KINGO_ID, member.get("kingo_id"));
                write(row, MAJOR, member.get("major_name"));
                write(row, ADMISSION_YEAR, member.get("admission_year"));
                write(row, GRADUATION_YEAR, member.get("graduation_year"));
                write(row, BIRTH_DATE, member.get("birth_date"));
                write(row, GENDER, genderLabel(member.get("gender")));
                write(row, PHONE, member.get("phone"));
                write(row, EMAIL, member.get("email"));
                write(row, OFFICER_ROLE, member.get("officer_role_name"));
                write(row, COMPANY, member.get("company_name"));
                write(row, JOB_TITLE, member.get("job_title"));
                write(row, HOME_ZIPCODE, member.get("home_zipcode"));
                write(row, HOME_ADDRESS1, member.get("home_address1"));
                write(row, HOME_ADDRESS2, member.get("home_address2"));
                write(row, DOWNLOAD_HEADERS.size() - 1, member.get("status"));
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
        Map<String, Long> majorIds = loadMajorIds();
        List<String> createdMajors = new java.util.ArrayList<>();
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
                String studentId = required(row, STUDENT_ID, "학번", excelRow, formatter);
                String name = required(row, NAME, "이름", excelRow, formatter);
                String majorName = required(row, MAJOR, "학과", excelRow, formatter);
                int admissionYear = parseYear(required(row, ADMISSION_YEAR, "입학연도", excelRow, formatter),
                        "입학연도", excelRow);
                Long majorId = findOrCreateMajor(majorIds, majorName, createdMajors);

                String companyName = value(row, COMPANY, formatter);
                Long companyId = StringUtils.hasText(companyName) ? findOrCreateCompany(companyName.trim()) : null;
                String phone = normalizePhone(nullable(row, PHONE, formatter));
                String email = nullable(row, EMAIL, formatter);
                String jobTitle = nullable(row, JOB_TITLE, formatter);
                String kingoId = nullable(row, KINGO_ID, formatter);
                Integer graduationYear = optionalYear(nullable(row, GRADUATION_YEAR, formatter), "졸업연도", excelRow);
                java.time.LocalDate birthDate = parseBirthDate(nullable(row, BIRTH_DATE, formatter), excelRow);
                String gender = parseGender(nullable(row, GENDER, formatter), excelRow);
                Long officerRoleId = resolveOfficerRole(nullable(row, OFFICER_ROLE, formatter), excelRow);

                // 기존 회원을 다시 부어도 이미 승인된 상태를 되돌리지 않는다.
                MemberColumns columns = new MemberColumns()
                        .set("name", name)
                        .set("major_id", majorId)
                        .set("admission_year", admissionYear)
                        .setIfPresent("kingo_id", kingoId)
                        .setIfPresent("graduation_year", graduationYear)
                        .setIfPresent("birth_date", birthDate)
                        .setIfPresent("gender", gender)
                        .setIfPresent("phone", phone)
                        .setIfPresent("email", email)
                        .setIfPresent("company_id", companyId)
                        .setIfPresent("job_title", jobTitle)
                        .setIfPresent("home_zipcode", nullable(row, HOME_ZIPCODE, formatter))
                        .setIfPresent("home_address1", nullable(row, HOME_ADDRESS1, formatter))
                        .setIfPresent("home_address2", nullable(row, HOME_ADDRESS2, formatter));

                Long memberId;
                try {
                    if (columns.update(jdbcTemplate, studentId) > 0) {
                        updated++;
                    } else {
                        jdbcTemplate.update("""
                                insert into users (student_id, name, kingo_id, password, category, degree, major_id,
                                    admission_year, graduation_year, birth_date, gender, phone, email,
                                    company_id, job_title, home_zipcode, home_address1, home_address2,
                                    status, created_at, updated_at)
                                values (?, ?, ?, '', 'UNDERGRADUATE', 'BACHELOR', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                    'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                                """, studentId, name, kingoId, majorId, admissionYear,
                                graduationYear, birthDate, gender, phone, email, companyId, jobTitle,
                                nullable(row, HOME_ZIPCODE, formatter), nullable(row, HOME_ADDRESS1, formatter),
                                nullable(row, HOME_ADDRESS2, formatter));
                        created++;
                    }
                } catch (DuplicateKeyException exception) {
                    // 학번은 위에서 기준으로 썼으니 남은 유일 제약은 킹고아이디뿐이다.
                    throw badRequest(excelRow + "행: 이미 다른 회원이 쓰고 있는 킹고아이디입니다. (" + kingoId + ")");
                }
                memberId = jdbcTemplate.queryForObject(
                        "select id from users where student_id = ?", Long.class, studentId);

                if (officerRoleId != null) {
                    officerAssignment.assign(memberId, currentTermId(excelRow), officerRoleId);
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("엑셀 파일을 읽을 수 없습니다. .xlsx 형식과 입력값을 확인해주세요.");
        }
        return Map.of("created", created, "updated", updated, "total", created + updated,
                "createdMajors", List.copyOf(createdMajors));
    }

    /**
     * 갱신할 칸만 모아 update 문을 만든다.
     *
     * <p>빈 칸을 그대로 덮어쓰면 안 된다. 사무처가 이름과 학과만 정리한 명단을
     * 다시 부었을 때, 비워둔 전화번호·이메일 칸 때문에 앱에서 회원이 직접 채워
     * 넣은 연락처가 통째로 날아간다. 값이 적힌 칸만 건드린다.
     *
     * <p>엑셀로 값을 지울 방법은 없어진다. 지우는 일은 회원 상세 화면에서 한다 —
     * 무엇을 지우는지 보면서 하는 편이 맞다.
     */
    private static final class MemberColumns {

        private final List<String> assignments = new java.util.ArrayList<>();
        private final List<Object> parameters = new java.util.ArrayList<>();

        MemberColumns set(String column, Object value) {
            assignments.add(column + " = ?");
            parameters.add(value);
            return this;
        }

        MemberColumns setIfPresent(String column, Object value) {
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                return this;
            }
            return set(column, value);
        }

        int update(JdbcTemplate jdbcTemplate, String studentId) {
            List<Object> arguments = new java.util.ArrayList<>(parameters);
            arguments.add(studentId);
            return jdbcTemplate.update(
                    "update users set " + String.join(", ", assignments)
                            + ", updated_at = CURRENT_TIMESTAMP where student_id = ?",
                    arguments.toArray());
        }
    }

    /**
     * 학과를 이름으로 찾기 위한 열쇠판. 업로드 한 번에 한 번만 읽는다.
     *
     * <p>줄마다 조회하면 5,000줄에 5,000번이고, 같은 파일 안에서 같은 학과가
     * 여러 번 새로 만들어지는 것도 이 판으로 막는다.
     *
     * <p>열쇠는 야간 표기와 공백·대소문자를 지운 이름이다({@link MajorNames#canonicalKey}).
     * '(야)법학과'를 적었다고 법학과 옆에 같은 학과가 하나 더 생기면 안 된다.
     * 비교를 SQL 이 아니라 자바에서 하는 이유는 {@link MajorNames} 에 적어둔 그대로다.
     *
     * <p>이름이 바뀐 학과는 적힌 이름 그대로 넣는다 — '산업공학과'로 부으면
     * 산업공학과로 저장되고, 화면에 보일 이름을 고르는 일은 조회할 때 따로 한다.
     */
    private Map<String, Long> loadMajorIds() {
        // 같은 열쇠를 가리키는 학과가 여럿이면 쓰이는 학과(ACTIVE)를 먼저 잡는다.
        List<Map<String, Object>> majors = jdbcTemplate.queryForList(
                "select id, name, normalized_name from majors order by case when status = 'ACTIVE' then 0 else 1 end, id");
        Map<String, Long> byName = new java.util.HashMap<>();
        for (Map<String, Object> major : majors) rememberMajor(byName, major.get("name"), major.get("id"));
        // 등록명(normalized_name)은 다음 차례다. 한 학과의 등록명이 다른 학과의 이름을 가로채면 안 된다.
        for (Map<String, Object> major : majors) rememberMajor(byName, major.get("normalized_name"), major.get("id"));
        return byName;
    }

    private void rememberMajor(Map<String, Long> byName, Object name, Object id) {
        if (name == null) return;
        String key = MajorNames.canonicalKey(name.toString());
        if (!key.isEmpty()) byName.putIfAbsent(key, ((Number) id).longValue());
    }

    /**
     * 명단에 적힌 학과를 찾고, 없으면 새로 등록한다.
     *
     * <p>처음 보는 학과 하나 때문에 5,000줄짜리 업로드를 통째로 되돌리면, 사무처는
     * 학과를 손으로 등록하고 처음부터 다시 올려야 한다. 회사명과 같이 넣고 넘어간다.
     *
     * <p>대신 새로 만든 학과는 응답에 담아 화면에 보여준다. 오타로 들어온 학과도
     * 그대로 등록되므로, 무엇이 생겼는지는 올린 사람이 바로 보고 알아차려야 한다.
     */
    private Long findOrCreateMajor(Map<String, Long> majorIds, String majorName, List<String> createdMajors) {
        String key = MajorNames.canonicalKey(majorName);
        Long existing = majorIds.get(key);
        if (existing != null) return existing;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into majors (name, normalized_name, status, created_at, updated_at)
                    values (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setString(1, majorName);
            statement.setString(2, majorName);
            return statement;
        }, keyHolder);
        Long id = keyHolder.getKey().longValue();
        majorIds.put(key, id);
        createdMajors.add(majorName);
        return id;
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

    private int parseYear(String value, String label, int row) {
        try {
            int year = Integer.parseInt(value.replace(".0", "").trim());
            if (year < 1900 || year > 2100) throw new NumberFormatException();
            return year;
        } catch (NumberFormatException exception) {
            throw badRequest(row + "행: " + label + "는 1900~2100 사이 숫자여야 합니다.");
        }
    }

    private Integer optionalYear(String value, String label, int row) {
        return StringUtils.hasText(value) ? parseYear(value, label, row) : null;
    }

    /**
     * 생년월일을 읽는다.
     *
     * <p>사무처가 받는 명단마다 표기가 제각각이라 한 형식만 받으면 매번 손으로
     * 고쳐야 한다. 자주 쓰는 네 형식을 모두 받는다.
     */
    private java.time.LocalDate parseBirthDate(String value, int row) {
        if (!StringUtils.hasText(value)) return null;
        String text = value.trim().replace(". ", "-").replace(".", "-").replace("/", "-");
        if (text.endsWith("-")) text = text.substring(0, text.length() - 1);
        try {
            if (text.matches("\\d{8}")) {
                return java.time.LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            String[] parts = text.split("-");
            if (parts.length != 3) throw new java.time.format.DateTimeParseException("", text, 0);
            return java.time.LocalDate.of(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (RuntimeException exception) {
            throw badRequest(row + "행: 생년월일은 1998-03-12 형식으로 적어주세요. (" + value + ")");
        }
    }

    private String parseGender(String value, int row) {
        if (!StringUtils.hasText(value)) return null;
        return switch (value.trim().toUpperCase(Locale.KOREA)) {
            case "남", "남자", "M", "MALE" -> "M";
            case "여", "여자", "F", "FEMALE" -> "F";
            default -> throw badRequest(row + "행: 성별은 남 또는 여로 적어주세요. (" + value + ")");
        };
    }

    /** 내려받은 파일을 그대로 다시 올릴 수 있도록 저장값(M/F)을 한글로 되돌린다. */
    private String genderLabel(Object gender) {
        if (gender == null) return "";
        return switch (String.valueOf(gender)) {
            case "M" -> "남";
            case "F" -> "여";
            default -> String.valueOf(gender);
        };
    }

    private Long resolveOfficerRole(String roleName, int row) {
        if (!StringUtils.hasText(roleName)) return null;
        return officerAssignment.findRoleIdOrNull(roleName)
                .orElseThrow(() -> badRequest(row + "행: 등록되지 않은 총동창회 직책입니다. (" + roleName + ")"));
    }

    /**
     * 직책을 붙일 임기. 양식에 임기 칸은 두지 않았다.
     *
     * <p>명단을 부을 때 사무처가 다루는 것은 언제나 지금 임기다. 지난 임기의
     * 이력을 손보는 일은 회원 상세 화면에서 한다.
     */
    private Long currentTermId(int row) {
        return officerAssignment.findCurrentTermIdOrNull()
                .orElseThrow(() -> badRequest(row + "행: 총동창회 직책을 적었지만 현행 임기가 없습니다. 임기를 먼저 등록해주세요."));
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
