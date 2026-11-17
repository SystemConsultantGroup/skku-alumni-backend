package com.scg.alumni.domain.organization;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CombinedRosterWorkbook {
    public static final List<String> HEADERS = headers();
    private CombinedRosterWorkbook() { }

    private static List<String> headers() {
        var result = new ArrayList<String>();
        result.add("단위동문회");
        result.addAll(AlumniRosterWorkbook.HEADERS);
        return List.copyOf(result);
    }

    public static String normalize(String name) {
        StringBuilder result = new StringBuilder();
        name.codePoints().filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(result::appendCodePoint);
        return result.toString();
    }

    public static Map<String, byte[]> split(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024) throw bad("파일 크기를 확인하세요.");
        try (var source = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (source.getNumberOfSheets() == 0) throw bad("시트가 없습니다.");
            var sheet = source.getSheetAt(0);
            var formatter = new DataFormatter(Locale.KOREA);
            var header = sheet.getRow(0);
            if (header == null || sheet.getLastRowNum() > 5000) throw bad("양식 또는 행 수를 확인하세요.");
            for (int i = 0; i < HEADERS.size(); i++) {
                if (!HEADERS.get(i).equals(text(header.getCell(i), formatter))) throw bad("통합 명단의 컬럼명과 순서를 확인하세요.");
            }
            Map<String, List<List<String>>> groups = new LinkedHashMap<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                List<String> values = new ArrayList<>();
                for (int c = 0; c < HEADERS.size(); c++) values.add(text(row.getCell(c), formatter));
                if (values.stream().allMatch(String::isBlank)) continue;
                String name = normalize(values.getFirst());
                if (name.isEmpty()) throw bad((r + 1) + "행: 단위동문회가 필요합니다.");
                groups.computeIfAbsent(name, ignored -> new ArrayList<>()).add(values.subList(1, values.size()));
            }
            if (groups.isEmpty()) throw bad("빈 명단은 업로드할 수 없습니다.");
            Map<String, byte[]> result = new LinkedHashMap<>();
            for (var group : groups.entrySet()) {
                try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
                    var target = workbook.createSheet();
                    Row targetHeader = target.createRow(0);
                    for (int c = 0; c < AlumniRosterWorkbook.HEADERS.size(); c++) targetHeader.createCell(c).setCellValue(AlumniRosterWorkbook.HEADERS.get(c));
                    for (int r = 0; r < group.getValue().size(); r++) {
                        Row row = target.createRow(r + 1);
                        for (int c = 0; c < AlumniRosterWorkbook.HEADERS.size(); c++) row.createCell(c).setCellValue(group.getValue().get(r).get(c));
                    }
                    workbook.write(out);
                    result.put(group.getKey(), out.toByteArray());
                }
            }
            return result;
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "엑셀 파일을 읽을 수 없습니다.", ex);
        }
    }

    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) throw bad("수식 셀은 사용할 수 없습니다.");
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        return formatter.formatCellValue(cell).trim();
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
