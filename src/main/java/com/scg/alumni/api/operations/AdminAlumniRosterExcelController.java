package com.scg.alumni.api.operations;

import com.scg.alumni.domain.organization.AlumniRosterSyncService;
import com.scg.alumni.domain.organization.AlumniRosterWorkbook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/alumni-organizations/{organizationId}/roster-excel")
@RequiredArgsConstructor
public class AdminAlumniRosterExcelController {
    private final AlumniRosterSyncService roster;
    private final AdminAuditLog audit;

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@PathVariable long organizationId) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("현직 명단");
            Row header = sheet.createRow(0);
            for (int index = 0; index < AlumniRosterWorkbook.HEADERS.size(); index++) {
                header.createCell(index).setCellValue(AlumniRosterWorkbook.HEADERS.get(index));
                sheet.setColumnWidth(index, 20 * 256);
                sheet.setDefaultColumnStyle(index, textStyle(workbook));
            }
            sheet.createFreezePane(0, 1);
            var guide = workbook.createSheet("작성 안내");
            guide.createRow(0).createCell(0).setCellValue("이 파일은 기존 현직 명단 전체를 교체합니다. 반드시 미리보기를 확인하세요.");
            guide.createRow(1).createCell(0).setCellValue("기존 회원은 회원ID와 이름을 입력합니다. 신규 회원은 ID를 비우고 후보를 확인합니다.");
            guide.createRow(2).createCell(0).setCellValue("회장: Y 또는 N. 날짜: YYYY-MM-DD 또는 엑셀 날짜. 학번과 전화번호는 선택입니다.");
            guide.createRow(3).createCell(0).setCellValue("동일인이 여러 직책을 맡을 때는 기존 회원ID를 각 행에 입력합니다.");
            guide.setColumnWidth(0, 120 * 256);
            workbook.write(out);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=alumni-roster.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    @PostMapping("/preview")
    public AlumniRosterSyncService.Preview preview(@PathVariable long organizationId, @RequestParam("file") MultipartFile file) throws IOException {
        return roster.preview(organizationId, bytes(file));
    }

    @PostMapping("/apply")
    @Transactional
    public AlumniRosterSyncService.Result apply(@PathVariable long organizationId, @RequestParam("file") MultipartFile file,
            @RequestParam String token, @RequestParam boolean confirmed) throws IOException {
        AlumniRosterSyncService.Result result = roster.apply(organizationId, bytes(file), token, confirmed);
        if (!result.unchanged()) audit.record("SYNC_ALUMNI_ROSTER", "alumni_organization", organizationId);
        return result;
    }

    private byte[] bytes(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 10L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 크기를 확인하세요.");
        }
        return file.getBytes();
    }

    private org.apache.poi.ss.usermodel.CellStyle textStyle(XSSFWorkbook workbook) {
        var style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("@"));
        return style;
    }
}
