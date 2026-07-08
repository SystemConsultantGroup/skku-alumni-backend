package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.global.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserFeatureController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/me")
    public Map<String, Object> findMe() {
        Long currentUserId = AuthContext.currentMemberId();
        return jdbcTemplate.queryForObject("""
                select u.id, u.name, u.student_id, u.email, u.phone, u.home_address1, u.home_address2,
                       u.admission_year, u.graduation_year, u.job_title, u.pr_text,
                       m.name as major_name, co.name as company_name, i.name as industry_name
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = u.industry_id
                where u.id = ?
                """, JdbcResponseMapper.INSTANCE, currentUserId);
    }

    @PatchMapping("/me")
    @Transactional
    public Map<String, Object> updateMe(@Valid @RequestBody ProfileUpdateRequest request) {
        Map<String, Object> current = findMe();

        jdbcTemplate.update("""
                update users
                set phone = ?, email = ?, home_address1 = ?, home_address2 = ?, pr_text = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """,
                request.phone(), request.email(), request.homeAddress1(), request.homeAddress2(), request.prText(), AuthContext.currentMemberId());

        logProfileChange("phone", current.get("phone"), request.phone());
        logProfileChange("email", current.get("email"), request.email());
        logProfileChange("home_address1", current.get("homeAddress1"), request.homeAddress1());
        logProfileChange("home_address2", current.get("homeAddress2"), request.homeAddress2());
        logProfileChange("pr_text", current.get("prText"), request.prText());

        return findMe();
    }

    @PostMapping("/member-applications")
    @Transactional
    public Map<String, Object> createApplication(@Valid @RequestBody MemberApplicationRequest request) {
        jdbcTemplate.update("""
                insert into member_applications (
                    name, student_id, phone, email, major_name, admission_year, desired_role,
                    company_name, job_title, status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                request.name(), request.studentId(), request.phone(), request.email(), request.majorName(),
                request.admissionYear(), request.desiredRole(), request.companyName(), request.jobTitle());

        Long id = jdbcTemplate.queryForObject("select max(id) from member_applications", Long.class);
        return Map.of("id", id, "status", "PENDING");
    }

    @GetMapping("/payments/me")
    public Map<String, Object> findMyPayment() {
        Long currentUserId = AuthContext.currentMemberId();
        List<Map<String, Object>> payments = jdbcTemplate.query("""
                select pr.id, pr.amount, pr.status, pr.paid_at,
                       ot.generation, ot.phase, ot.started_at, ot.ended_at,
                       orole.name as officer_role_name
                from payment_records pr
                join officer_terms ot on ot.id = pr.officer_term_id
                join officer_histories oh on oh.user_id = pr.user_id and oh.officer_term_id = pr.officer_term_id
                join officer_roles orole on orole.id = oh.officer_role_id
                where pr.user_id = ?
                order by ot.generation desc, ot.phase desc
                """, JdbcResponseMapper.INSTANCE, currentUserId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", payments);
        response.put("current", payments.isEmpty() ? null : payments.get(0));
        return response;
    }

    @GetMapping("/clubs")
    public List<Map<String, Object>> findClubs(@RequestParam(required = false) String category) {
        if (StringUtils.hasText(category)) {
            return jdbcTemplate.query("""
                    select c.id, c.name, c.description, c.category, u.name as president_name,
                           count(cm.id) as member_count
                    from clubs c
                    left join users u on u.id = c.president_user_id
                    left join club_members cm on cm.club_id = c.id and cm.left_at is null
                    where c.category = ?
                    group by c.id, c.name, c.description, c.category, u.name
                    order by c.id desc
                    """, JdbcResponseMapper.INSTANCE, category.toUpperCase(Locale.ROOT));
        }

        return jdbcTemplate.query("""
                select c.id, c.name, c.description, c.category, u.name as president_name,
                       count(cm.id) as member_count
                from clubs c
                left join users u on u.id = c.president_user_id
                left join club_members cm on cm.club_id = c.id and cm.left_at is null
                group by c.id, c.name, c.description, c.category, u.name
                order by c.id desc
                """, JdbcResponseMapper.INSTANCE);
    }

    @GetMapping("/notices")
    public CursorPageResponse<Map<String, Object>> findNotices(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return findPosts("NOTICE", cursor, size);
    }

    @GetMapping("/business-posts")
    public CursorPageResponse<Map<String, Object>> findBusinessPosts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return findPosts("BUSINESS", cursor, size);
    }

    @PostMapping("/reports")
    @Transactional
    public Map<String, Object> createReport(@Valid @RequestBody ReportCreateRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        jdbcTemplate.update("""
                insert into reports (
                    reporter_id, target_type, target_post_id, reason, reason_others, status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, currentUserId, request.targetType(), request.targetPostId(), request.reason(), request.reasonOthers());

        Long id = jdbcTemplate.queryForObject("select max(id) from reports", Long.class);
        return Map.of("id", id, "status", "PENDING");
    }

    @GetMapping("/blocked-users")
    public List<Map<String, Object>> findBlockedUsers() {
        Long currentUserId = AuthContext.currentMemberId();
        return jdbcTemplate.query("""
                select ub.id, ub.blocked_id, u.name, u.phone, m.name as major_name, ub.created_at
                from user_blocks ub
                join users u on u.id = ub.blocked_id
                join majors m on m.id = u.major_id
                where ub.blocker_id = ?
                order by ub.id desc
                """, JdbcResponseMapper.INSTANCE, currentUserId);
    }

    @PostMapping("/blocked-users")
    @Transactional
    public Map<String, Object> blockUser(@Valid @RequestBody BlockUserRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        if (request.blockedId().equals(currentUserId)) {
            throw new IllegalArgumentException("본인은 차단할 수 없습니다.");
        }
        jdbcTemplate.update("""
                insert into user_blocks (blocker_id, blocked_id, created_at, updated_at)
                values (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, currentUserId, request.blockedId());
        return Map.of("blockedId", request.blockedId());
    }

    @DeleteMapping("/blocked-users/{blockedId}")
    @Transactional
    public Map<String, Object> unblockUser(@PathVariable Long blockedId) {
        jdbcTemplate.update("delete from user_blocks where blocker_id = ? and blocked_id = ?", AuthContext.currentMemberId(), blockedId);
        return Map.of("blockedId", blockedId);
    }

    private CursorPageResponse<Map<String, Object>> findPosts(String kind, Long cursor, Integer size) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at, u.name as author_name,
                       i.name as industry_name
                from posts p
                join users u on u.id = p.user_id
                left join industries i on i.id = p.industry_id
                where p.status = 'PUBLISHED'
                  and p.post_kind = ?
                  and (? is null or p.id < ?)
                order by p.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE, kind, cursor, cursor, CursorPageFactory.queryLimit(size));

        return CursorPageFactory.from(rows, size);
    }

    private void logProfileChange(String fieldName, Object oldValue, Object newValue) {
        if ((oldValue == null && newValue == null) || (oldValue != null && oldValue.equals(newValue))) {
            return;
        }
        jdbcTemplate.update("""
                insert into profile_change_logs (
                    user_id, field_name, old_value, new_value, synced, changed_at, created_at, updated_at
                ) values (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, AuthContext.currentMemberId(), fieldName, oldValue == null ? null : oldValue.toString(),
                newValue == null ? null : newValue.toString());
    }

    public record ProfileUpdateRequest(
            String phone,
            String email,
            String homeAddress1,
            String homeAddress2,
            String prText
    ) {
    }

    public record MemberApplicationRequest(
            @NotBlank String name,
            @NotBlank String studentId,
            String phone,
            String email,
            String majorName,
            Integer admissionYear,
            String desiredRole,
            String companyName,
            String jobTitle
    ) {
    }

    public record ReportCreateRequest(
            @NotBlank String targetType,
            @NotNull Long targetPostId,
            @NotBlank String reason,
            String reasonOthers
    ) {
    }

    public record BlockUserRequest(
            @NotNull Long blockedId
    ) {
    }
}
