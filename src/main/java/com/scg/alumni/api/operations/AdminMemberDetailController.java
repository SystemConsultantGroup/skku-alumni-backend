package com.scg.alumni.api.operations;

import com.scg.alumni.infrastructure.image.ProfileImageStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회원 한 명에 딸린 모든 데이터를 한 화면에 모아 준다.
 *
 * <p>지금까지 사무처는 회원 목록에서 상태만 바꿀 수 있었다. 학과가 잘못 들어갔거나
 * 동호회 가입이 남아 있는 것을 고치려면 DB를 직접 열어야 했다. 회원을 누르면 열리는
 * 상세 화면이 그 자리를 대신한다.
 *
 * <p>삭제는 모두 deleted_at 을 채우는 것으로 끝난다. 실수로 지운 것을 되돌릴 수
 * 없으면 사무처가 이 화면을 쓰지 못한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/{memberId}")
public class AdminMemberDetailController {

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditLog adminAuditLog;
    private final ProfileImageStorage profileImageStorage;

    @GetMapping
    public Map<String, Object> findMember(@PathVariable Long memberId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profile", findProfile(memberId));
        response.put("hobbies", findHobbies(memberId));
        response.put("webLinks", findWebLinks(memberId));
        response.put("clubMemberships", findClubMemberships(memberId));
        response.put("clubApplications", findClubApplications(memberId));
        response.put("officerHistories", findOfficerHistories(memberId));
        response.put("payments", findPayments(memberId));
        response.put("posts", findPosts(memberId));
        response.put("reportsFiled", findReportsFiled(memberId));
        response.put("reportsReceived", findReportsReceived(memberId));
        response.put("blocks", findBlocks(memberId));
        response.put("blockedBy", findBlockedBy(memberId));
        response.put("deviceTokens", findDeviceTokens(memberId));
        response.put("profileChangeLogs", findProfileChangeLogs(memberId));
        return response;
    }

    @PatchMapping
    @Transactional
    public Map<String, Object> updateMember(
            @PathVariable Long memberId,
            @Valid @RequestBody MemberProfileRequest request
    ) {
        requireMember(memberId);
        String status = normalizeStatus(request.status());
        try {
            jdbcTemplate.update("""
                    update users
                    set name = ?, student_id = ?, kingo_id = ?, birth_date = ?, gender = ?,
                        category = ?, degree = ?, major_id = ?, admission_year = ?, graduation_year = ?,
                        nationality = ?, home_zipcode = ?, home_address1 = ?, home_address2 = ?,
                        work_zipcode = ?, work_address1 = ?, work_address2 = ?,
                        phone = ?, email = ?, email_receive = ?, industry_id = ?, company_id = ?,
                        job_title = ?, pr_text = ?, status = ?,
                        birth_date_public = ?, phone_public = ?, email_public = ?, home_address_public = ?,
                        notification_enabled = ?, notice_notification_enabled = ?, club_notification_enabled = ?,
                        updated_at = CURRENT_TIMESTAMP
                    where id = ?
                    """,
                    blankToNull(request.name()), blankToNull(request.studentId()), blankToNull(request.kingoId()),
                    request.birthDate(), upperOrNull(request.gender()),
                    upperOrNull(request.category()), upperOrNull(request.degree()), request.majorId(),
                    request.admissionYear(), request.graduationYear(),
                    blankToNull(request.nationality()), blankToNull(request.homeZipcode()),
                    blankToNull(request.homeAddress1()), blankToNull(request.homeAddress2()),
                    blankToNull(request.workZipcode()), blankToNull(request.workAddress1()),
                    blankToNull(request.workAddress2()),
                    blankToNull(request.phone()), blankToNull(request.email()), isTrue(request.emailReceive()),
                    request.industryId(), request.companyId(),
                    blankToNull(request.jobTitle()), blankToNull(request.prText()), status,
                    isTrue(request.birthDatePublic()), isTrue(request.phonePublic()),
                    isTrue(request.emailPublic()), isTrue(request.homeAddressPublic()),
                    isTrue(request.notificationEnabled()), isTrue(request.noticeNotificationEnabled()),
                    isTrue(request.clubNotificationEnabled()),
                    memberId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 학번 또는 킹고아이디입니다.");
        }
        adminAuditLog.record("UPDATE_MEMBER_PROFILE", "user", memberId);
        return findProfile(memberId);
    }

    /**
     * 회원을 지운다. 행은 남기고 지운 시각만 찍는다.
     *
     * <p>status 도 WITHDRAWN 으로 함께 바꾼다. 이미 코드 곳곳이 그 값을 "보이지
     * 않는 회원"으로 다루고 있어, 지운 회원이 남의 화면에 남는 일을 막는다.
     * 본인 탈퇴와 달리 개인정보는 비우지 않는다. 되살릴 수 있어야 하기 때문이다.
     */
    @DeleteMapping
    @Transactional
    public Map<String, Object> deleteMember(@PathVariable Long memberId) {
        requireMember(memberId);
        jdbcTemplate.update("""
                update users
                set deleted_at = CURRENT_TIMESTAMP, status = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null
                """, memberId);
        adminAuditLog.record("DELETE_MEMBER", "user", memberId);
        return Map.of("id", memberId, "deleted", true);
    }

    @PostMapping("/restore")
    @Transactional
    public Map<String, Object> restoreMember(@PathVariable Long memberId) {
        requireMember(memberId);
        jdbcTemplate.update("""
                update users
                set deleted_at = null, status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is not null
                """, memberId);
        adminAuditLog.record("RESTORE_MEMBER", "user", memberId);
        return findProfile(memberId);
    }

    @PostMapping("/profile-image")
    @Transactional
    public Map<String, Object> uploadProfileImage(
            @PathVariable Long memberId,
            @RequestPart("file") MultipartFile file
    ) {
        requireMember(memberId);
        String imageUrl = profileImageStorage.store(file);
        jdbcTemplate.update("""
                update users
                set profile_image_url = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, imageUrl, memberId);
        adminAuditLog.record("UPDATE_MEMBER_PROFILE_IMAGE", "user", memberId);
        return Map.of("profileImageUrl", imageUrl);
    }

    @DeleteMapping("/profile-image")
    @Transactional
    public Map<String, Object> removeProfileImage(@PathVariable Long memberId) {
        requireMember(memberId);
        jdbcTemplate.update("""
                update users
                set profile_image_url = null, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, memberId);
        adminAuditLog.record("REMOVE_MEMBER_PROFILE_IMAGE", "user", memberId);
        // Map.of 는 null 값을 담으면 NPE 를 던진다. 그러면 @Transactional 이
        // 방금 지운 것을 되돌리고, 화면에는 500 만 남아 사진이 그대로 남는다.
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", memberId);
        response.put("profileImageUrl", null);
        return response;
    }

    private Map<String, Object> findProfile(Long memberId) {
        return jdbcTemplate.query("""
                select u.id, u.name, u.student_id, u.kingo_id, u.login_id, u.birth_date, u.gender, u.category, u.degree,
                       u.major_id, u.admission_year, u.graduation_year, u.nationality,
                       u.home_zipcode, u.home_address1, u.home_address2,
                       u.work_zipcode, u.work_address1, u.work_address2,
                       u.phone, u.email, u.email_receive, u.industry_id, u.company_id, u.job_title,
                       u.status, u.pr_text, u.profile_image_url, u.deleted_at,
                       u.birth_date_public, u.phone_public, u.email_public, u.home_address_public,
                       u.notification_enabled, u.notice_notification_enabled, u.club_notification_enabled,
                       u.created_at, u.updated_at,
                       m.name as major_name, co.name as company_name, i.name as industry_name
                from users u
                left join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = u.industry_id
                where u.id = ?
                """, JdbcResponseMapper.INSTANCE, memberId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
    }

    private List<Map<String, Object>> findHobbies(Long memberId) {
        return jdbcTemplate.query("""
                select uh.id, uh.hobby_id, h.name, uh.created_at
                from user_hobbies uh
                join hobbies h on h.id = uh.hobby_id
                where uh.user_id = ? and uh.deleted_at is null and h.deleted_at is null
                order by h.name
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findWebLinks(Long memberId) {
        return jdbcTemplate.query("""
                select id, url, created_at
                from user_web_links
                where user_id = ? and deleted_at is null
                order by id
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findClubMemberships(Long memberId) {
        return jdbcTemplate.query("""
                select cm.id, cm.club_id, c.name as club_name, c.category as club_category,
                       cm.club_role, cm.joined_at, cm.left_at
                from club_members cm
                join clubs c on c.id = cm.club_id
                where cm.user_id = ? and cm.deleted_at is null
                order by case when cm.left_at is null then 0 else 1 end, c.name
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findClubApplications(Long memberId) {
        return jdbcTemplate.query("""
                select a.id, a.club_id, c.name as club_name, a.status, a.applied_at, a.reviewed_at
                from club_join_applications a
                join clubs c on c.id = a.club_id
                where a.user_id = ?
                order by a.applied_at desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findOfficerHistories(Long memberId) {
        return jdbcTemplate.query("""
                select oh.id, oh.officer_term_id, ot.generation, ot.phase,
                       oh.officer_role_id, orole.name as officer_role_name,
                       oh.started_at, oh.ended_at, oh.payment_status
                from officer_histories oh
                join officer_terms ot on ot.id = oh.officer_term_id
                join officer_roles orole on orole.id = oh.officer_role_id
                where oh.user_id = ? and oh.deleted_at is null
                order by ot.generation desc, ot.phase desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findPayments(Long memberId) {
        return jdbcTemplate.query("""
                select pr.id, pr.officer_term_id, ot.generation, ot.phase,
                       pr.amount, pr.status, pr.paid_at
                from payment_records pr
                join officer_terms ot on ot.id = pr.officer_term_id
                where pr.user_id = ? and pr.deleted_at is null
                order by ot.generation desc, ot.phase desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findPosts(Long memberId) {
        return jdbcTemplate.query("""
                select p.id, p.title, p.body, p.post_kind, p.status, p.thumbnail_url,
                       p.club_id, c.name as club_name, p.industry_id, i.name as industry_name,
                       p.created_at, p.updated_at,
                       (select count(*) from reports r where r.target_post_id = p.id and r.deleted_at is null)
                           as report_count
                from posts p
                left join clubs c on c.id = p.club_id
                left join industries i on i.id = p.industry_id
                where p.user_id = ? and p.deleted_at is null
                order by p.id desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findReportsFiled(Long memberId) {
        return jdbcTemplate.query("""
                select r.id, r.target_post_id, p.title as target_title, r.reason, r.reason_others,
                       r.status, r.admin_memo, r.created_at, r.resolved_at
                from reports r
                left join posts p on p.id = r.target_post_id
                where r.reporter_id = ? and r.deleted_at is null
                order by r.id desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findReportsReceived(Long memberId) {
        return jdbcTemplate.query("""
                select r.id, r.target_post_id, p.title as target_title, r.reason, r.reason_others,
                       r.status, r.admin_memo, r.created_at, r.resolved_at,
                       reporter.name as reporter_name
                from reports r
                join posts p on p.id = r.target_post_id
                left join users reporter on reporter.id = r.reporter_id
                where p.user_id = ? and r.deleted_at is null
                order by r.id desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findBlocks(Long memberId) {
        return jdbcTemplate.query("""
                select ub.id, ub.blocked_id, u.name as blocked_name, u.student_id as blocked_student_id,
                       ub.created_at
                from user_blocks ub
                left join users u on u.id = ub.blocked_id
                where ub.blocker_id = ? and ub.deleted_at is null
                order by ub.id desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findBlockedBy(Long memberId) {
        return jdbcTemplate.query("""
                select ub.id, ub.blocker_id, u.name as blocker_name, u.student_id as blocker_student_id,
                       ub.created_at
                from user_blocks ub
                left join users u on u.id = ub.blocker_id
                where ub.blocked_id = ? and ub.deleted_at is null
                order by ub.id desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findDeviceTokens(Long memberId) {
        return jdbcTemplate.query("""
                select id, token, platform, last_seen_at, created_at
                from device_tokens
                where user_id = ? and deleted_at is null
                order by last_seen_at desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private List<Map<String, Object>> findProfileChangeLogs(Long memberId) {
        return jdbcTemplate.query("""
                select id, field_name, old_value, new_value, synced, changed_at, synced_at
                from profile_change_logs
                where user_id = ? and deleted_at is null
                order by id desc
                """, JdbcResponseMapper.INSTANCE, memberId);
    }

    private void requireMember(Long memberId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where id = ?", Integer.class, memberId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        }
    }

    private String normalizeStatus(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회원 상태가 필요합니다.");
        }
        String status = value.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "PENDING", "ACTIVE", "REJECTED", "WITHDRAWN" -> status;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 회원 상태입니다.");
        };
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String upperOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean isTrue(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    public record MemberProfileRequest(
            @Size(max = 255) String name,
            @Size(max = 255) String studentId,
            @Size(max = 255) String kingoId,
            LocalDate birthDate,
            String gender,
            String category,
            String degree,
            Long majorId,
            Integer admissionYear,
            Integer graduationYear,
            @Size(max = 255) String nationality,
            @Size(max = 50) String homeZipcode,
            @Size(max = 255) String homeAddress1,
            @Size(max = 255) String homeAddress2,
            @Size(max = 50) String workZipcode,
            @Size(max = 255) String workAddress1,
            @Size(max = 255) String workAddress2,
            @Size(max = 50) String phone,
            @Email @Size(max = 255) String email,
            Boolean emailReceive,
            Long industryId,
            Long companyId,
            @Size(max = 255) String jobTitle,
            String prText,
            String status,
            Boolean birthDatePublic,
            Boolean phonePublic,
            Boolean emailPublic,
            Boolean homeAddressPublic,
            Boolean notificationEnabled,
            Boolean noticeNotificationEnabled,
            Boolean clubNotificationEnabled
    ) {
    }
}
