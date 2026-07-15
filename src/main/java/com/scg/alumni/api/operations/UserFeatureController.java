package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.global.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
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
        Map<String, Object> profile = jdbcTemplate.queryForObject("""
                select u.id, u.name, u.student_id, u.email, u.phone, u.home_address1, u.home_address2,
                       u.birth_date, u.birth_date_public, u.phone_public, u.email_public,
                       u.admission_year, u.graduation_year, u.job_title, u.pr_text, u.company_id,
                       m.name as major_name, co.name as company_name,
                       co.work_zipcode as company_zipcode, co.work_address1 as company_address1,
                       co.work_address2 as company_address2, co.description as company_description,
                       co.industry_id, i.name as industry_name,
                       ot.generation as officer_generation, ot.phase as officer_phase,
                       orole.name as officer_role_name
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = co.industry_id
                left join officer_terms ot on ot.current_term = true
                left join officer_histories oh on oh.user_id = u.id and oh.officer_term_id = ot.id
                left join officer_roles orole on orole.id = oh.officer_role_id
                where u.id = ?
                """, JdbcResponseMapper.INSTANCE, currentUserId);
        profile.put("hobbies", jdbcTemplate.query("""
                select h.id, h.name
                from user_hobbies uh
                join hobbies h on h.id = uh.hobby_id
                where uh.user_id = ?
                order by h.name
                """, JdbcResponseMapper.INSTANCE, currentUserId));
        profile.put("webLinks", jdbcTemplate.queryForList("""
                select url from user_web_links where user_id = ? order by id
                """, String.class, currentUserId));
        return profile;
    }

    @PatchMapping("/me")
    @Transactional
    public Map<String, Object> updateMe(@Valid @RequestBody ProfileUpdateRequest request) {
        Map<String, Object> current = findMe();

        jdbcTemplate.update(
                """
                        update users
                        set birth_date = ?, birth_date_public = ?, phone = ?, phone_public = ?,
                            email = ?, email_public = ?, home_address1 = ?, home_address2 = ?,
                            company_id = ?, industry_id = (select industry_id from companies where id = ?),
                            job_title = ?, pr_text = ?, updated_at = CURRENT_TIMESTAMP
                        where id = ?
                        """,
                request.birthDate(), request.birthDatePublic(), request.phone(), request.phonePublic(),
                request.email(), request.emailPublic(), request.homeAddress1(), request.homeAddress2(),
                request.companyId(), request.companyId(), request.jobTitle(), request.prText(),
                AuthContext.currentMemberId());

        replaceHobbies(AuthContext.currentMemberId(), request.hobbyIds());
        replaceWebLinks(AuthContext.currentMemberId(), request.webLinks());

        logProfileChange("phone", current.get("phone"), request.phone());
        logProfileChange("email", current.get("email"), request.email());
        logProfileChange("birth_date", current.get("birthDate"), request.birthDate());
        logProfileChange("home_address1", current.get("homeAddress1"), request.homeAddress1());
        logProfileChange("home_address2", current.get("homeAddress2"), request.homeAddress2());
        logProfileChange("pr_text", current.get("prText"), request.prText());
        logProfileChange("company_id", current.get("companyId"), request.companyId());
        logProfileChange("job_title", current.get("jobTitle"), request.jobTitle());

        return findMe();
    }

    @GetMapping("/members/{memberId}")
    public Map<String, Object> findMemberDetail(@PathVariable Long memberId) {
        Map<String, Object> member = jdbcTemplate.query("""
                select u.id, u.name, u.admission_year, u.graduation_year, u.job_title, u.pr_text,
                       case when u.birth_date_public then u.birth_date else null end as birth_date,
                       case when u.phone_public then u.phone else null end as phone,
                       case when u.email_public then u.email else null end as email,
                       m.name as major_name, co.name as company_name,
                       co.work_zipcode as company_zipcode, co.work_address1 as company_address1,
                       co.work_address2 as company_address2, co.description as company_description,
                       i.name as industry_name, ot.generation as officer_generation,
                       ot.phase as officer_phase, orole.name as officer_role_name
                from users u
                join majors m on m.id = u.major_id
                join officer_terms ot on ot.current_term = true
                join officer_histories oh on oh.user_id = u.id and oh.officer_term_id = ot.id
                join officer_roles orole on orole.id = oh.officer_role_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = co.industry_id
                where u.id = ? and u.status = 'ACTIVE' and oh.payment_status = 'PAID'
                """, JdbcResponseMapper.INSTANCE, memberId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("임원 정보를 찾을 수 없습니다."));
        member.put("hobbies", jdbcTemplate.queryForList("""
                select h.name from user_hobbies uh join hobbies h on h.id = uh.hobby_id
                where uh.user_id = ? order by h.name
                """, String.class, memberId));
        member.put("webLinks", jdbcTemplate.queryForList("""
                select url from user_web_links where user_id = ? order by id
                """, String.class, memberId));
        return member;
    }

    @PostMapping("/companies")
    @Transactional
    public Map<String, Object> createCompany(@Valid @RequestBody CompanyCreateRequest request) {
        Integer duplicateCount = jdbcTemplate.queryForObject(
                "select count(*) from companies where lower(trim(name)) = lower(trim(?))", Integer.class, request.name());
        if (duplicateCount != null && duplicateCount > 0) {
            throw new IllegalArgumentException("이미 등록된 회사명입니다.");
        }
        Integer industryCount = jdbcTemplate.queryForObject(
                "select count(*) from industries where id = ?", Integer.class, request.industryId());
        if (industryCount == null || industryCount == 0) {
            throw new IllegalArgumentException("존재하지 않는 사업 분야입니다.");
        }
        jdbcTemplate.update("""
                insert into companies (
                    name, work_zipcode, work_address1, work_address2, industry_id, description, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, request.name().trim(), request.workZipcode(), request.workAddress1(), request.workAddress2(),
                request.industryId(), request.description());
        Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        return jdbcTemplate.queryForObject("""
                select c.id, c.name, c.work_zipcode, c.work_address1, c.work_address2,
                       c.description, c.industry_id, i.name as industry_name
                from companies c join industries i on i.id = c.industry_id where c.id = ?
                """, JdbcResponseMapper.INSTANCE, id);
    }

    private void replaceHobbies(Long userId, List<Long> hobbyIds) {
        List<Long> uniqueIds = hobbyIds == null ? List.of() : hobbyIds.stream().distinct().toList();
        if (!uniqueIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(uniqueIds.size(), "?"));
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from hobbies where id in (" + placeholders + ")",
                    Integer.class, uniqueIds.toArray());
            if (count == null || count != uniqueIds.size()) {
                throw new IllegalArgumentException("존재하지 않는 취미가 포함되어 있습니다.");
            }
        }
        jdbcTemplate.update("delete from user_hobbies where user_id = ?", userId);
        uniqueIds.forEach(hobbyId -> jdbcTemplate.update("""
                insert into user_hobbies (user_id, hobby_id, created_at, updated_at)
                values (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, hobbyId));
    }

    private void replaceWebLinks(Long userId, List<String> webLinks) {
        List<String> normalizedLinks = webLinks == null ? List.of() : webLinks.stream()
                .map(String::trim).filter(StringUtils::hasText).distinct().toList();
        normalizedLinks.forEach(this::validateWebLink);
        jdbcTemplate.update("delete from user_web_links where user_id = ?", userId);
        normalizedLinks.forEach(url -> jdbcTemplate.update("""
                insert into user_web_links (user_id, url, created_at, updated_at)
                values (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, url));
    }

    private void validateWebLink(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException("웹 링크는 http 또는 https URL이어야 합니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("올바르지 않은 웹 링크가 포함되어 있습니다.");
        }
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
                request.name(), blankToEmpty(request.studentId()), request.phone(), request.email(),
                request.majorName(),
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

    @GetMapping("/clubs/{clubId}")
    public Map<String, Object> findClub(@PathVariable Long clubId) {
        return jdbcTemplate.queryForObject("""
                select c.id, c.name, c.description, c.category, president.name as president_name,
                       max(secretary.name) as secretary_name, count(distinct cm.id) as member_count,
                       count(distinct p.id) as post_count
                from clubs c
                left join users president on president.id = c.president_user_id
                left join club_members cm on cm.club_id = c.id and cm.left_at is null
                left join club_members secretary_member on secretary_member.club_id = c.id
                       and secretary_member.left_at is null and secretary_member.club_role = 'SECRETARY'
                left join users secretary on secretary.id = secretary_member.user_id
                left join posts p on p.club_id = c.id and p.post_kind = 'CLUB' and p.status = 'PUBLISHED'
                where c.id = ?
                group by c.id, c.name, c.description, c.category, president.name
                """, JdbcResponseMapper.INSTANCE, clubId);
    }

    @GetMapping("/clubs/{clubId}/members")
    public List<Map<String, Object>> findClubMembers(@PathVariable Long clubId) {
        return jdbcTemplate.query("""
                select cm.id, cm.club_role, cm.joined_at, u.id as user_id, u.name,
                       u.admission_year, u.job_title, m.name as major_name,
                       co.name as company_name, i.name as industry_name
                from club_members cm
                join users u on u.id = cm.user_id
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = u.industry_id
                where cm.club_id = ? and cm.left_at is null
                order by case cm.club_role when 'PRESIDENT' then 1 when 'SECRETARY' then 2 else 3 end, u.name
                """, JdbcResponseMapper.INSTANCE, clubId);
    }

    @GetMapping("/clubs/{clubId}/posts")
    public List<Map<String, Object>> findClubPosts(@PathVariable Long clubId) {
        return jdbcTemplate.query("""
                select p.id, p.title, p.body, p.created_at, u.name as author_name
                from posts p
                join users u on u.id = p.user_id
                where p.club_id = ? and p.post_kind = 'CLUB' and p.status = 'PUBLISHED'
                order by p.id desc
                """, JdbcResponseMapper.INSTANCE, clubId);
    }

    @GetMapping("/notices")
    public CursorPageResponse<Map<String, Object>> findNotices(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return findPosts("NOTICE", cursor, size, null);
    }

    @GetMapping("/news")
    public CursorPageResponse<Map<String, Object>> findNews(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return findPosts("NEWS", cursor, size, null);
    }

    @GetMapping("/business-posts")
    public CursorPageResponse<Map<String, Object>> findBusinessPosts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long industryId) {
        return findPosts("BUSINESS", cursor, size, industryId);
    }

    @PostMapping("/reports")
    @Transactional
    public Map<String, Object> createReport(@Valid @RequestBody ReportCreateRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        jdbcTemplate.update("""
                insert into reports (
                    reporter_id, target_type, target_post_id, reason, reason_others, status, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, currentUserId, request.targetType(), request.targetPostId(), request.reason(),
                request.reasonOthers());

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
        jdbcTemplate.update("delete from user_blocks where blocker_id = ? and blocked_id = ?",
                AuthContext.currentMemberId(), blockedId);
        return Map.of("blockedId", blockedId);
    }

    private CursorPageResponse<Map<String, Object>> findPosts(
            String kind, Long cursor, Integer size, Long industryId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at, u.name as author_name,
                       p.industry_id, i.name as industry_name
                from posts p
                join users u on u.id = p.user_id
                left join industries i on i.id = p.industry_id
                where p.status = 'PUBLISHED'
                  and p.post_kind = ?
                  and (? is null or p.id < ?)
                  and (? is null or p.industry_id = ?)
                order by p.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE, kind, cursor, cursor, industryId, industryId,
                CursorPageFactory.queryLimit(size));

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

    private String blankToEmpty(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim();
    }

    public record ProfileUpdateRequest(
            LocalDate birthDate,
            boolean birthDatePublic,
            String phone,
            boolean phonePublic,
            String email,
            boolean emailPublic,
            String homeAddress1,
            String homeAddress2,
            Long companyId,
            @Size(max = 255) String jobTitle,
            List<Long> hobbyIds,
            List<@Size(max = 1000) String> webLinks,
            @Size(max = 500) String prText) {
    }

    public record CompanyCreateRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 50) String workZipcode,
            @Size(max = 255) String workAddress1,
            @Size(max = 255) String workAddress2,
            @NotNull Long industryId,
            @Size(max = 1000) String description) {
    }

    public record MemberApplicationRequest(
            @NotBlank String name,
            String studentId,
            @NotBlank String phone,
            String email,
            @NotBlank String majorName,
            @NotNull Integer admissionYear,
            @NotBlank String desiredRole,
            String companyName,
            String jobTitle) {
    }

    public record ReportCreateRequest(
            @NotBlank String targetType,
            @NotNull Long targetPostId,
            @NotBlank String reason,
            String reasonOthers) {
    }

    public record BlockUserRequest(
            @NotNull Long blockedId) {
    }
}
