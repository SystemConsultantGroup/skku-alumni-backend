package com.scg.alumni.domain.organization;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Parser for the current-roster replacement workbook. */
public final class AlumniRosterWorkbook {
    public static final List<String> HEADERS = List.of("회원ID", "이름", "직책", "회장", "학번", "전화번호", "시작일", "종료일");
    private AlumniRosterWorkbook() { }

    public static List<Entry> parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw bad("파일 크기를 확인하세요.");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw bad("시트가 없습니다.");
            var sheet = workbook.getSheetAt(0);
            var format = new DataFormatter(Locale.KOREA);
            Row header = sheet.getRow(0);
            if (header == null) throw bad("첫 행에 컬럼명이 필요합니다.");
            for (int column = 0; column < HEADERS.size(); column++) {
                if (!HEADERS.get(column).equals(text(header, column, format))) throw bad("양식의 컬럼명과 순서를 확인하세요.");
            }
            if (sheet.getLastRowNum() > 5000) throw bad("최대 5000행까지 등록할 수 있습니다.");
            List<Entry> entries = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            Set<String> studentIds = new HashSet<>();
            Set<String> phones = new HashSet<>();
            int presidents = 0;
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                List<String> fields = new ArrayList<>();
                for (int col = 0; col < HEADERS.size(); col++) fields.add(text(row, col, format));
                if (fields.stream().allMatch(String::isBlank)) continue;
                String name = fields.get(1), title = fields.get(2), marker = fields.get(3).toUpperCase(Locale.ROOT);
                if (name.isBlank()) throw bad((index + 1) + "행: 이름이 필요합니다.");
                if (!Set.of("Y", "N").contains(marker)) throw bad((index + 1) + "행: 회장은 Y 또는 N으로 입력하세요.");
                boolean president = "Y".equals(marker);
                try { title = AlumniOrganizationService.normalizeTitle(title, president); }
                catch (ResponseStatusException ex) { throw bad((index + 1) + "행: " + ex.getReason()); }
                if (president && ++presidents > 1) throw bad("현직 회장은 한 명만 등록할 수 있습니다.");
                Long userId = null;
                if (!fields.get(0).isBlank()) {
                    try { userId = Long.valueOf(fields.get(0)); }
                    catch (NumberFormatException ex) { throw bad((index + 1) + "행: 회원ID가 올바르지 않습니다."); }
                    if (userId <= 0) throw bad((index + 1) + "행: 회원ID가 올바르지 않습니다.");
                }
                String studentId = emptyToNull(fields.get(4));
                String phone = emptyToNull(fields.get(5));
                if (name.length() > 255 || title.length() > 255 || (studentId != null && studentId.length() > 255)
                        || (phone != null && (phone.length() > 50 || !phone.matches("[0-9 .-]+")
                            || phone.replaceAll("[^0-9]", "").length() < 4))) {
                    throw bad((index + 1) + "행: 이름, 직책 또는 연락처를 확인하세요.");
                }
                LocalDate start = date(row, 6, format, index + 1), end = date(row, 7, format, index + 1);
                if (start != null && end != null && end.isBefore(start)) throw bad((index + 1) + "행: 임기 날짜가 올바르지 않습니다.");
                String key = (userId == null ? "new:" + name + ":" + studentId + ":" + phone : "id:" + userId) + ":" + title;
                if (!keys.add(key)) throw bad((index + 1) + "행: 동일한 직책이 중복됩니다.");
                // A person may hold multiple titles, but new rows require explicit IDs to make that unambiguous.
                if (userId == null && studentId != null && !studentIds.add(studentId)) throw bad((index + 1) + "행: 학번이 중복됩니다.");
                if (userId == null && phone != null && !phones.add(phone.replaceAll("[^0-9]", ""))) throw bad((index + 1) + "행: 전화번호가 중복됩니다.");
                entries.add(new Entry(index + 1, userId, name, title, president, studentId, phone, start, end));
            }
            return List.copyOf(entries);
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "엑셀 파일을 읽을 수 없습니다.", ex);
        }
    }

    private static String text(Row row, int index, DataFormatter format) {
        Cell cell = row.getCell(index);
        if (cell != null && cell.getCellType() == CellType.FORMULA) throw bad("수식 셀은 사용할 수 없습니다.");
        return cell == null ? "" : format.formatCellValue(cell).trim();
    }

    private static LocalDate date(Row row, int index, DataFormatter format, int rowNumber) {
        Cell cell = row.getCell(index);
        if (cell == null || text(row, index, format).isBlank()) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate();
            return LocalDate.parse(text(row, index, format));
        } catch (RuntimeException ex) { throw bad(rowNumber + "행: 날짜는 YYYY-MM-DD 형식이어야 합니다."); }
    }

    private static String emptyToNull(String value) { return value.isBlank() ? null : value; }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record Entry(int row, Long userId, String name, String title, boolean president, String studentId,
                        String phone, LocalDate startedAt, LocalDate endedAt) { }
}
