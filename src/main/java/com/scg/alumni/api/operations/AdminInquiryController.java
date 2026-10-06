package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.PageResponse;
import com.scg.alumni.domain.inquiry.InquiryCategory;
import com.scg.alumni.global.security.AdminRoleGuard;
import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.infrastructure.push.PushNotificationService;
import com.scg.alumni.infrastructure.storage.InquiryAttachmentStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 사무처의 문의 관리. 목록·상세·등록·수정·삭제와 답변, 그리고 접수 알림을 받을 메일 주소 관리.
 *
 * <p>접수 전(DRAFT)인 문의는 어느 화면에도 나오지 않는다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminInquiryController {

    private static final int TITLE_MAX = 200;
    private static final int BODY_MAX = 5000;
    private static final int MAX_NOTIFY_EMAILS = 20;

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditLog adminAuditLog;
    private final AdminRoleGuard adminRoleGuard;
    private final InquiryAttachmentStorage attachmentStorage;
    private final PushNotificationService pushNotificationService;

    public record InquiryBody(
            @NotBlank String category,
            @NotBlank @Size(max = TITLE_MAX) String title,
            @NotBlank @Size(max = BODY_MAX) String body
    ) {
    }

    public record InquiryCreateRequest(
            @NotNull Long userId,
            @NotBlank String category,
            @NotBlank @Size(max = TITLE_MAX) String title,
            @NotBlank @Size(max = BODY_MAX) String body
    ) {
    }

    public record AnswerRequest(@NotBlank @Size(max = BODY_MAX) String body) {
    }

    public record NotifyEmailRequest(@NotBlank @Email @Size(max = 255) String email) {
    }

    @GetMapping("/inquiries")
    public PageResponse<Map<String, Object>> findInquiries(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        String like = StringUtils.hasText(keyword) ? "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%" : null;
        String normalizedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        String normalizedCategory = StringUtils.hasText(category) ? category.trim() : null;
        return AdminPaging.query(jdbcTemplate, """
                select i.id, i.category, i.title, i.status, i.created_at, i.answered_at,
                       u.id as user_id, u.name, u.student_id,
                       (select count(*) from inquiry_attachments a where a.inquiry_id = i.id) as attachment_count
                from inquiries i
                join users u on u.id = i.user_id
                where i.deleted_at is null and i.status <> 'DRAFT'
                  and (? is null or lower(i.title) like ? or lower(i.body) like ? or lower(coalesce(u.name, '')) like ?)
                  and (? is null or i.status = ?)
                  and (? is null or i.category = ?)
                """, "order by i.id desc", AdminPaging.args(
                like, like, like, like,
                normalizedStatus, normalizedStatus,
                normalizedCategory, normalizedCategory), page, size);
    }

    @GetMapping("/inquiries/{id}")
    public Map<String, Object> findInquiry(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select i.id, i.category, i.title, i.body, i.status, i.created_at, i.updated_at,
                       i.answer_body, i.answered_at, ans.name as answered_by_name,
                       u.id as user_id, u.name, u.student_id, u.phone, u.email
                from inquiries i
                join users u on u.id = i.user_id
                left join admins ans on ans.id = i.answered_by
                where i.id = ? and i.deleted_at is null and i.status <> 'DRAFT'
                """, JdbcResponseMapper.INSTANCE, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
        }
        Map<String, Object> inquiry = new LinkedHashMap<>(rows.get(0));
        inquiry.put("attachments", jdbcTemplate.query("""
                select id, original_name as name, size_bytes as size, content_type
                from inquiry_attachments where inquiry_id = ? order by id
                """, JdbcResponseMapper.INSTANCE, id));
        return inquiry;
    }

    /**
     * 사무처가 회원 대신 문의를 남긴다. 전화로 받은 문의를 기록하는 용도다.
     * 이미 접수된 상태로 만들어지고 알림 메일은 나가지 않는다 — 접수한 사람이 곧 사무처다.
     */
    @PostMapping("/inquiries")
    public Map<String, Object> createInquiry(@Valid @RequestBody InquiryCreateRequest request) {
        InquiryCategory category = parseCategory(request.category());
        Integer member = jdbcTemplate.queryForObject(
                "select count(*) from users where id = ? and deleted_at is null", Integer.class, request.userId());
        if (member == null || member == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "문의를 남길 회원을 찾을 수 없습니다.");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into inquiries (user_id, category, title, body, status, created_at, updated_at)
                    values (?, ?, ?, ?, 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setLong(1, request.userId());
            statement.setString(2, category.name());
            statement.setString(3, request.title().trim());
            statement.setString(4, request.body().trim());
            return statement;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        adminAuditLog.record("CREATE_INQUIRY", "inquiry", id);
        return Map.of("id", id);
    }

    @PatchMapping("/inquiries/{id}")
    @Transactional
    public Map<String, Object> updateInquiry(@PathVariable Long id, @Valid @RequestBody InquiryBody request) {
        InquiryCategory category = parseCategory(request.category());
        int updated = jdbcTemplate.update("""
                update inquiries set category = ?, title = ?, body = ?, updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null and status <> 'DRAFT'
                """, category.name(), request.title().trim(), request.body().trim(), id);
        requireFound(updated);
        adminAuditLog.record("UPDATE_INQUIRY", "inquiry", id);
        return Map.of("id", id);
    }

    @DeleteMapping("/inquiries/{id}")
    @Transactional
    public Map<String, Object> deleteInquiry(@PathVariable Long id) {
        int updated = jdbcTemplate.update("""
                update inquiries set deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null and status <> 'DRAFT'
                """, id);
        requireFound(updated);
        adminAuditLog.record("DELETE_INQUIRY", "inquiry", id);
        return Map.of("id", id);
    }

    /**
     * 답변을 달거나 고친다. 처음 답을 달 때만 회원에게 알림이 간다 — 오타를 고칠 때마다
     * 푸시가 울리면 회원은 새 답이 달린 줄 안다.
     */
    @PutMapping("/inquiries/{id}/answer")
    @Transactional
    public Map<String, Object> answer(@PathVariable Long id, @Valid @RequestBody AnswerRequest request) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select user_id, title, answer_body from inquiries
                where id = ? and deleted_at is null and status <> 'DRAFT'
                """, JdbcResponseMapper.INSTANCE, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "문의를 찾을 수 없습니다.");
        }
        Map<String, Object> inquiry = rows.get(0);
        boolean first = inquiry.get("answerBody") == null;
        jdbcTemplate.update("""
                update inquiries
                set answer_body = ?, status = 'ANSWERED', answered_by = ?,
                    answered_at = case when answered_at is null then CURRENT_TIMESTAMP else answered_at end,
                    updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, request.body().trim(), AuthContext.currentAdminId(), id);
        adminAuditLog.record(first ? "ANSWER_INQUIRY" : "UPDATE_INQUIRY_ANSWER", "inquiry", id);
        if (first) {
            pushNotificationService.notifyInquiryAnswer(
                    ((Number) inquiry.get("userId")).longValue(), id, (String) inquiry.get("title"));
        }
        return Map.of("id", id, "notified", first);
    }

    /** 답변을 거두고 미답변으로 되돌린다. 이미 나간 알림은 회수되지 않는다. */
    @DeleteMapping("/inquiries/{id}/answer")
    @Transactional
    public Map<String, Object> deleteAnswer(@PathVariable Long id) {
        int updated = jdbcTemplate.update("""
                update inquiries
                set answer_body = null, answered_by = null, answered_at = null, status = 'OPEN',
                    updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null and status = 'ANSWERED'
                """, id);
        requireFound(updated);
        adminAuditLog.record("DELETE_INQUIRY_ANSWER", "inquiry", id);
        return Map.of("id", id);
    }

    @GetMapping("/inquiries/{id}/attachments/{attachmentId}")
    public ResponseEntity<StreamingResponseBody> downloadAttachment(
            @PathVariable Long id, @PathVariable Long attachmentId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select a.object_name, a.original_name, a.size_bytes
                from inquiry_attachments a
                join inquiries i on i.id = a.inquiry_id
                where a.id = ? and i.id = ? and i.deleted_at is null and i.status <> 'DRAFT'
                """, JdbcResponseMapper.INSTANCE, attachmentId, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "첨부 파일을 찾을 수 없습니다.");
        }
        Map<String, Object> row = rows.get(0);
        return attachmentStorage.download((String) row.get("objectName"), (String) row.get("originalName"),
                ((Number) row.get("sizeBytes")).longValue());
    }

    // ---- 접수 알림을 받을 메일 주소

    @GetMapping("/inquiry-notify-emails")
    public List<Map<String, Object>> findNotifyEmails() {
        return jdbcTemplate.query(
                "select id, email, created_at from inquiry_notify_emails order by id", JdbcResponseMapper.INSTANCE);
    }

    /** 알림 받을 주소를 정하는 일은 사무총장만 한다. 문의 내용이 그 주소로 나간다. */
    @PostMapping("/inquiry-notify-emails")
    @Transactional
    public Map<String, Object> addNotifyEmail(@Valid @RequestBody NotifyEmailRequest request) {
        adminRoleGuard.requireMaster();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Integer count = jdbcTemplate.queryForObject("select count(*) from inquiry_notify_emails", Integer.class);
        if (count != null && count >= MAX_NOTIFY_EMAILS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "알림 받을 메일은 " + MAX_NOTIFY_EMAILS + "개까지 등록할 수 있습니다.");
        }
        try {
            jdbcTemplate.update(
                    "insert into inquiry_notify_emails (email, created_at) values (?, CURRENT_TIMESTAMP)", email);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 등록된 메일 주소입니다.");
        }
        Long id = jdbcTemplate.queryForObject("select id from inquiry_notify_emails where email = ?", Long.class, email);
        adminAuditLog.record("ADD_INQUIRY_NOTIFY_EMAIL", "inquiry_notify_email", id);
        return Map.of("id", id, "email", email);
    }

    @DeleteMapping("/inquiry-notify-emails/{id}")
    @Transactional
    public Map<String, Object> removeNotifyEmail(@PathVariable Long id) {
        adminRoleGuard.requireMaster();
        requireFound(jdbcTemplate.update("delete from inquiry_notify_emails where id = ?", id));
        adminAuditLog.record("REMOVE_INQUIRY_NOTIFY_EMAIL", "inquiry_notify_email", id);
        return Map.of("id", id);
    }

    private InquiryCategory parseCategory(String code) {
        return InquiryCategory.from(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "문의 분류가 올바르지 않습니다."));
    }

    private void requireFound(int updated) {
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.");
        }
    }
}
