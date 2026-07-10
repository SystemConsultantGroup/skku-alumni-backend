package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.global.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminFeatureController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/dashboard")
    public Map<String, Object> findDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", Map.of(
                "activeMembers", count("select count(*) from users where status = 'ACTIVE'"),
                "pendingMembers", count("select count(*) from users where status = 'PENDING'"),
                "paidCurrentOfficers", count("""
                        select count(*)
                        from officer_histories oh
                        join officer_terms ot on ot.id = oh.officer_term_id
                        where ot.current_term = true and oh.payment_status = 'PAID'
                        """),
                "unpaidCurrentOfficers", count("""
                        select count(*)
                        from officer_histories oh
                        join officer_terms ot on ot.id = oh.officer_term_id
                        where ot.current_term = true and oh.payment_status = 'UNPAID'
                        """),
                "pendingApplications", count("select count(*) from member_applications where status = 'PENDING'"),
                "pendingReports", count("select count(*) from reports where status = 'PENDING'"),
                "pendingAsisSync", count("select count(*) from profile_change_logs where synced = false")
        ));
        response.put("recentApplications", jdbcTemplate.query("""
                select id, name, major_name, admission_year, desired_role, status, created_at
                from member_applications
                order by id desc
                limit 5
                """, JdbcResponseMapper.INSTANCE));
        response.put("recentPayments", jdbcTemplate.query("""
                select pr.id, u.name, orole.name as officer_role_name, pr.amount, pr.status, pr.paid_at
                from payment_records pr
                join users u on u.id = pr.user_id
                join officer_histories oh on oh.user_id = pr.user_id and oh.officer_term_id = pr.officer_term_id
                join officer_roles orole on orole.id = oh.officer_role_id
                order by pr.id desc
                limit 5
                """, JdbcResponseMapper.INSTANCE));
        return response;
    }

    @GetMapping("/members")
    public CursorPageResponse<Map<String, Object>> findMembers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String memberStatus,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedKeyword = normalizeLike(keyword);
        String normalizedPaymentStatus = normalizeUpper(paymentStatus);
        String normalizedMemberStatus = normalizeUpper(memberStatus);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select u.id, u.name, u.student_id, u.phone, u.email, u.status,
                       m.name as major_name, co.name as company_name, u.job_title,
                       ot.generation, ot.phase, orole.name as officer_role_name,
                       oh.payment_status
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join officer_histories oh on oh.user_id = u.id
                left join officer_terms ot on ot.id = oh.officer_term_id and ot.current_term = true
                left join officer_roles orole on orole.id = oh.officer_role_id
                where (? is null or u.id < ?)
                  and (? is null or lower(u.name) like ? or lower(m.name) like ? or lower(coalesce(co.name, '')) like ?)
                  and (? is null or oh.payment_status = ?)
                  and (? is null or u.status = ?)
                order by u.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor,
                normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedPaymentStatus, normalizedPaymentStatus,
                normalizedMemberStatus, normalizedMemberStatus,
                CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PatchMapping("/members/{id}/status")
    @Transactional
    public Map<String, Object> updateMemberStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        String status = normalizeRequiredStatus(request.status(), "PENDING", "ACTIVE", "REJECTED");
        int updated = jdbcTemplate.update("""
                update users
                set status = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, status, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        }

        audit("UPDATE_MEMBER_STATUS", "user", id);
        return Map.of("id", id, "status", status);
    }

    @GetMapping("/applications")
    public CursorPageResponse<Map<String, Object>> findApplications(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedStatus = normalizeUpper(status);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, name, student_id, phone, email, major_name, admission_year, desired_role,
                       company_name, job_title, status, reviewed_at, created_at
                from member_applications
                where (? is null or id < ?)
                  and (? is null or status = ?)
                order by id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor, normalizedStatus, normalizedStatus, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PatchMapping("/applications/{id}/status")
    @Transactional
    public Map<String, Object> updateApplicationStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        jdbcTemplate.update("""
                update member_applications
                set status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, request.status(), AuthContext.currentAdminId(), id);
        audit("UPDATE_APPLICATION_STATUS", "member_application", id);
        return Map.of("id", id, "status", request.status());
    }

    @GetMapping("/payments")
    public CursorPageResponse<Map<String, Object>> findPayments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedStatus = normalizeUpper(status);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select pr.id, u.name, u.student_id, pr.amount, pr.status, pr.paid_at,
                       ot.generation, ot.phase, orole.name as officer_role_name
                from payment_records pr
                join users u on u.id = pr.user_id
                join officer_terms ot on ot.id = pr.officer_term_id
                join officer_histories oh on oh.user_id = pr.user_id and oh.officer_term_id = pr.officer_term_id
                join officer_roles orole on orole.id = oh.officer_role_id
                where (? is null or pr.id < ?)
                  and (? is null or pr.status = ?)
                order by pr.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor, normalizedStatus, normalizedStatus, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PatchMapping("/payments/{id}/status")
    @Transactional
    public Map<String, Object> updatePaymentStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        jdbcTemplate.update("""
                update payment_records
                set status = ?, paid_at = case when ? = 'PAID' then CURRENT_TIMESTAMP else null end, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, request.status(), request.status(), id);
        audit("UPDATE_PAYMENT_STATUS", "payment_record", id);
        return Map.of("id", id, "status", request.status());
    }

    @GetMapping("/asis-sync")
    public CursorPageResponse<Map<String, Object>> findAsisSyncTargets(
            @RequestParam(required = false) Boolean synced,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select pcl.id, pcl.field_name, pcl.old_value, pcl.new_value, pcl.synced,
                       pcl.changed_at, pcl.synced_at, u.id as user_id, u.name, u.student_id
                from profile_change_logs pcl
                join users u on u.id = pcl.user_id
                where (? is null or pcl.id < ?)
                  and (? is null or pcl.synced = ?)
                order by pcl.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor, synced, synced, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PatchMapping("/asis-sync/{id}/mark-synced")
    @Transactional
    public Map<String, Object> markAsisSynced(@PathVariable Long id) {
        jdbcTemplate.update("""
                update profile_change_logs
                set synced = true, synced_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, id);
        audit("MARK_ASIS_SYNCED", "profile_change_log", id);
        return Map.of("id", id, "synced", true);
    }

    @GetMapping("/reports")
    public CursorPageResponse<Map<String, Object>> findReports(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedStatus = normalizeUpper(status);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select r.id, r.target_type, r.target_post_id, r.reason, r.reason_others,
                       r.status, r.admin_memo, r.created_at, r.resolved_at,
                       reporter.name as reporter_name, p.title as target_title
                from reports r
                join users reporter on reporter.id = r.reporter_id
                join posts p on p.id = r.target_post_id
                where (? is null or r.id < ?)
                  and (? is null or r.status = ?)
                order by r.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor, normalizedStatus, normalizedStatus, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PatchMapping("/reports/{id}/status")
    @Transactional
    public Map<String, Object> updateReportStatus(
            @PathVariable Long id,
            @Valid @RequestBody ReportStatusUpdateRequest request
    ) {
        jdbcTemplate.update("""
                update reports
                set status = ?, admin_memo = ?, resolved_by = ?, resolved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, request.status(), request.adminMemo(), AuthContext.currentAdminId(), id);
        audit("UPDATE_REPORT_STATUS", "report", id);
        return Map.of("id", id, "status", request.status());
    }

    @GetMapping("/managers")
    public List<Map<String, Object>> findManagers() {
        return jdbcTemplate.query("""
                select a.id, a.email, a.name, a.position, a.active, ar.name as role_name, a.created_at
                from admins a
                join admin_roles ar on ar.id = a.admin_role_id
                order by a.id
                """, JdbcResponseMapper.INSTANCE);
    }

    @GetMapping("/audit-logs")
    public CursorPageResponse<Map<String, Object>> findAuditLogs(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select log.id, admin.name as admin_name, log.action, log.target_type, log.target_id, log.created_at
                from admin_audit_logs log
                join admins admin on admin.id = log.admin_id
                where (? is null or log.id < ?)
                order by log.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE, cursor, cursor, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private String normalizeLike(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeUpper(String value) {
        if (!StringUtils.hasText(value) || "all".equalsIgnoreCase(value)) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRequiredStatus(String value, String... allowedStatuses) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "상태값이 필요합니다.");
        }
        String status = value.trim().toUpperCase(Locale.ROOT);
        for (String allowedStatus : allowedStatuses) {
            if (allowedStatus.equals(status)) {
                return status;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 상태값입니다.");
    }

    private void audit(String action, String targetType, Long targetId) {
        jdbcTemplate.update("""
                insert into admin_audit_logs (admin_id, action, target_type, target_id, created_at)
                values (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, AuthContext.currentAdminId(), action, targetType, targetId);
    }

    public record StatusUpdateRequest(
            @NotBlank String status
    ) {
    }

    public record ReportStatusUpdateRequest(
            @NotBlank String status,
            String adminMemo
    ) {
    }
}
