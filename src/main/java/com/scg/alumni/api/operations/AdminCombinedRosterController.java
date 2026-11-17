package com.scg.alumni.api.operations;

import com.scg.alumni.domain.organization.CombinedRosterService;
import com.scg.alumni.domain.organization.CombinedRosterWorkbook;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/alumni-roster-uploads")
public class AdminCombinedRosterController {
    private final CombinedRosterService service;

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() throws IOException {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("단위동문회 통합 명단");
            var header = sheet.createRow(0);
            var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("@"));
            for (int i = 0; i < CombinedRosterWorkbook.HEADERS.size(); i++) {
                header.createCell(i).setCellValue(CombinedRosterWorkbook.HEADERS.get(i));
                sheet.setColumnWidth(i, 20 * 256); sheet.setDefaultColumnStyle(i, style);
            }
            sheet.createFreezePane(0, 1);
            book.write(out);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=combined-alumni-roster.xlsx")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }

    @PostMapping("/preview")
    public CombinedRosterService.Preview preview(@RequestParam("file") MultipartFile file) throws IOException { return service.preview(bytes(file)); }
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestParam("file") MultipartFile file, @RequestParam String token,
                                      @RequestParam boolean confirmed) throws IOException { return service.apply(bytes(file), token, confirmed); }
    @GetMapping
    public List<Map<String, Object>> jobs(@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size) { return service.jobs(cursor, size); }
    @GetMapping("/{jobId}")
    public List<Map<String, Object>> details(@PathVariable long jobId) { return service.details(jobId); }
    @PostMapping("/{jobId}/rollback/preview")
    public List<Map<String, Object>> rollbackPreview(@PathVariable long jobId, @Valid @RequestBody Selection request) { return service.rollbackPreview(jobId, request.organizationIds()); }
    @PostMapping("/{jobId}/rollback")
    public List<Map<String, Object>> rollback(@PathVariable long jobId, @Valid @RequestBody Selection request) { return service.rollback(jobId, request.organizationIds()); }

    private byte[] bytes(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 10L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 크기를 확인하세요.");
        return file.getBytes();
    }
    public record Selection(@NotEmpty List<Long> organizationIds) { }
}
