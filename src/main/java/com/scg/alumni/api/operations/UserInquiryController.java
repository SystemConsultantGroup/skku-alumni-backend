package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.domain.inquiry.InquiryCategory;
import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.infrastructure.mail.InquiryMailService;
import com.scg.alumni.infrastructure.storage.InquiryAttachmentStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 회원이 총동창회 사무처에 하는 문의.
 *
 * <p>채팅이 아니라 한 번 묻고 한 번 답을 받는 구조다. 접수는 세 걸음으로 나뉜다.
 * 문의 만들기(임시) → 첨부 올리기(개당 한 요청) → 접수. 첨부를 문의와 한 요청에 담으면
 * 요청이 최대 250MB 가 되어 중간 프록시나 모바일 회선에서 끊긴다. 접수 전에는 사무처
 * 화면과 알림 메일에 나오지 않는다.
 *
 * <p>모든 조회는 로그인한 회원 본인의 문의로만 좁힌다.
 */
@RestController
@RequestMapping("/api/v1/me/inquiries")
@RequiredArgsConstructor
public class UserInquiryController {

    private static final int TITLE_MAX = 200;
    private static final int BODY_MAX = 5000;

    private final JdbcTemplate jdbcTemplate;
    private final InquiryAttachmentStorage attachmentStorage;
    private final InquiryMailService inquiryMailService;

    public record InquiryRequest(
            @NotBlank String category,
            @NotBlank @Size(max = TITLE_MAX) String title,
            @NotBlank @Size(max = BODY_MAX) String body
    ) {
    }

    /** 내 문의. 접수된 것만, 최신순. */
    @GetMapping
    public CursorPageResponse<Map<String, Object>> findMine(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, category, title, status, created_at, answered_at
                from inquiries
                where user_id = ? and deleted_at is null and status <> 'DRAFT'
                  and (? is null or id < ?)
                order by id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                AuthContext.currentMemberId(), cursor, cursor, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> findOne(@PathVariable Long id) {
        Long userId = AuthContext.currentMemberId();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, category, title, body, status, answer_body, answered_at, created_at
                from inquiries
                where id = ? and user_id = ? and deleted_at is null and status <> 'DRAFT'
                """, JdbcResponseMapper.INSTANCE, id, userId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
        }
        Map<String, Object> inquiry = new LinkedHashMap<>(rows.get(0));
        inquiry.put("attachments", attachments(id));
        return inquiry;
    }

    /** 문의를 임시로 만든다. 접수는 {@link #submit} 에서 된다. */
    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody InquiryRequest request) {
        InquiryCategory category = InquiryCategory.from(request.category())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "문의 분류를 선택해주세요."));
        Long userId = AuthContext.currentMemberId();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into inquiries (user_id, category, title, body, status, created_at, updated_at)
                    values (?, ?, ?, ?, 'DRAFT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setLong(1, userId);
            statement.setString(2, category.name());
            statement.setString(3, request.title().trim());
            statement.setString(4, request.body().trim());
            return statement;
        }, keyHolder);
        return Map.of("id", keyHolder.getKey().longValue());
    }

    /** 첨부 하나를 올린다. 접수 전의 내 문의에만, 최대 5개까지. */
    @PostMapping("/{id}/attachments")
    public Map<String, Object> addAttachment(@PathVariable Long id, @RequestPart("file") MultipartFile file) {
        requireDraft(id);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from inquiry_attachments where inquiry_id = ?", Integer.class, id);
        if (count != null && count >= InquiryAttachmentStorage.MAX_FILES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "첨부 파일은 " + InquiryAttachmentStorage.MAX_FILES + "개까지 올릴 수 있습니다.");
        }
        InquiryAttachmentStorage.Stored stored = attachmentStorage.store(file);
        jdbcTemplate.update("""
                insert into inquiry_attachments (inquiry_id, original_name, object_name, content_type, size_bytes, created_at)
                values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, stored.originalName(), stored.objectName(), stored.contentType(), stored.size());
        return Map.of("name", stored.originalName(), "size", stored.size());
    }

    /** 문의를 접수한다. 이미 접수된 문의에 다시 불러도 알림 메일이 두 번 나가지 않는다. */
    @PostMapping("/{id}/submit")
    public Map<String, Object> submit(@PathVariable Long id) {
        Long userId = AuthContext.currentMemberId();
        int updated = jdbcTemplate.update("""
                update inquiries set status = 'OPEN', created_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ? and user_id = ? and deleted_at is null and status = 'DRAFT'
                """, id, userId);
        if (updated == 0) {
            Integer exists = jdbcTemplate.queryForObject("""
                    select count(*) from inquiries where id = ? and user_id = ? and deleted_at is null
                    """, Integer.class, id, userId);
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
            }
            return Map.of("id", id);
        }
        inquiryMailService.notifyReceived(id);
        return Map.of("id", id);
    }

    @GetMapping("/{id}/attachments/{attachmentId}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable Long id, @PathVariable Long attachmentId) {
        // 내 문의의 첨부만 내려준다. 번호를 안다고 남의 파일을 받을 수 있어서는 안 된다.
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select a.object_name, a.original_name, a.size_bytes
                from inquiry_attachments a
                join inquiries i on i.id = a.inquiry_id
                where a.id = ? and i.id = ? and i.user_id = ? and i.deleted_at is null and i.status <> 'DRAFT'
                """, JdbcResponseMapper.INSTANCE, attachmentId, id, AuthContext.currentMemberId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부 파일을 찾을 수 없습니다.");
        }
        Map<String, Object> row = rows.get(0);
        return attachmentStorage.download((String) row.get("objectName"), (String) row.get("originalName"),
                ((Number) row.get("sizeBytes")).longValue());
    }

    private void requireDraft(Long id) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from inquiries
                where id = ? and user_id = ? and deleted_at is null and status = 'DRAFT'
                """, Integer.class, id, AuthContext.currentMemberId());
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부를 올릴 수 있는 문의가 아닙니다.");
        }
    }

    private List<Map<String, Object>> attachments(Long inquiryId) {
        return jdbcTemplate.query("""
                select id, original_name as name, size_bytes as size, content_type
                from inquiry_attachments where inquiry_id = ? order by id
                """, JdbcResponseMapper.INSTANCE, inquiryId);
    }
}
