package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.api.common.MarkdownImageExtractor;
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
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserFeatureController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/me")
    public Map<String, Object> findMe() {
        Long currentUserId = AuthContext.currentMemberId();
        Map<String, Object> profile = jdbcTemplate.queryForObject("""
                select u.id, u.name, u.student_id, u.email, u.phone, u.home_zipcode, u.home_address1, u.home_address2,
                       u.birth_date, u.birth_date_public, u.phone_public, u.email_public,
                       u.home_address_public, u.notification_enabled,
                       u.admission_year, u.graduation_year, u.job_title, u.pr_text, u.company_id,
                       u.work_zipcode, u.work_address1, u.work_address2,
                       m.name as major_name, co.name as company_name,
                       u.work_zipcode as company_zipcode, u.work_address1 as company_address1,
                       u.work_address2 as company_address2, co.description as company_description,
                       u.industry_id, i.name as industry_name,
                       ot.generation as officer_generation, ot.phase as officer_phase,
                       orole.name as officer_role_name
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = u.industry_id
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
                            email = ?, email_public = ?, home_zipcode = ?, home_address1 = ?, home_address2 = ?, home_address_public = ?,
                            company_id = ?, industry_id = ?, work_zipcode = ?, work_address1 = ?, work_address2 = ?,
                            job_title = ?, pr_text = ?, updated_at = CURRENT_TIMESTAMP
                        where id = ?
                        """,
                request.birthDate(), request.birthDatePublic(), request.phone(), request.phonePublic(),
                request.email(), request.emailPublic(), request.homeZipcode(), request.homeAddress1(), request.homeAddress2(), request.homeAddressPublic(),
                request.companyId(), request.industryId(), request.workZipcode(), request.workAddress1(), request.workAddress2(),
                request.jobTitle(), request.prText(),
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

    @PatchMapping("/me/preferences")
    @Transactional
    public Map<String, Object> updatePreferences(@Valid @RequestBody PreferenceUpdateRequest request) {
        jdbcTemplate.update("""
                update users
                set notification_enabled = ?, phone_public = ?, email_public = ?,
                    home_address_public = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, request.notificationEnabled(), request.phonePublic(), request.emailPublic(),
                request.homeAddressPublic(), AuthContext.currentMemberId());
        return findMe();
    }

    @DeleteMapping("/me")
    @Transactional
    public Map<String, Object> withdrawMe() {
        Long currentUserId = AuthContext.currentMemberId();
        List<Map<String, Object>> leaderships = jdbcTemplate.query("""
                select name,
                       case when president_user_id = ? then '회장' else '총무' end as role_name
                from clubs
                where president_user_id = ? or manager_user_id = ?
                order by id
                """, JdbcResponseMapper.INSTANCE, currentUserId, currentUserId, currentUserId);
        if (!leaderships.isEmpty()) {
            String clubs = leaderships.stream()
                    .map(leadership -> leadership.get("name") + " " + leadership.get("roleName"))
                    .collect(Collectors.joining(", "));
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    clubs + "으로 활동 중입니다. 회원 탈퇴 전에 다른 회원을 후임으로 임명해주세요.");
        }

        jdbcTemplate.update("""
                update club_members
                set left_at = CURRENT_TIMESTAMP
                where user_id = ? and left_at is null
                """, currentUserId);
        jdbcTemplate.update("delete from user_hobbies where user_id = ?", currentUserId);
        jdbcTemplate.update("delete from user_web_links where user_id = ?", currentUserId);
        jdbcTemplate.update("delete from profile_change_logs where user_id = ?", currentUserId);
        jdbcTemplate.update(
                "delete from user_blocks where blocker_id = ? or blocked_id = ?",
                currentUserId,
                currentUserId);
        jdbcTemplate.update("""
                update users
                set student_id = null, kingo_id = null, name = null, password = null,
                    birth_date = null, gender = null, category = null, degree = null,
                    major_id = null, admission_year = null, graduation_year = null,
                    nationality = null,
                    home_zipcode = null, home_address1 = null, home_address2 = null,
                    phone = null, email = null, email_receive = false,
                    industry_id = null, company_id = null, job_title = null, pr_text = null,
                    work_zipcode = null, work_address1 = null, work_address2 = null,
                    birth_date_public = false, phone_public = false, email_public = false,
                    home_address_public = false, notification_enabled = false,
                    status = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP
                where id = ? and status <> 'WITHDRAWN'
                """, currentUserId);

        return Map.of("status", "WITHDRAWN");
    }

    @GetMapping("/members/{memberId}")
    public Map<String, Object> findMemberDetail(@PathVariable Long memberId) {
        Map<String, Object> member = jdbcTemplate.query("""
                select u.id, u.name, u.admission_year, u.graduation_year, u.job_title, u.pr_text,
                       case when u.birth_date_public then u.birth_date else null end as birth_date,
                       case when u.phone_public then u.phone else null end as phone,
                       case when u.email_public then u.email else null end as email,
                       case when u.home_address_public then u.home_address1 else null end as home_address1,
                       case when u.home_address_public then u.home_address2 else null end as home_address2,
                       m.name as major_name, co.name as company_name,
                       u.work_zipcode as company_zipcode, u.work_address1 as company_address1,
                       u.work_address2 as company_address2, co.description as company_description,
                       i.name as industry_name, ot.generation as officer_generation,
                       ot.phase as officer_phase, orole.name as officer_role_name
                from users u
                join majors m on m.id = u.major_id
                join officer_terms ot on ot.current_term = true
                join officer_histories oh on oh.user_id = u.id and oh.officer_term_id = ot.id
                join officer_roles orole on orole.id = oh.officer_role_id
                left join companies co on co.id = u.company_id
                left join industries i on i.id = u.industry_id
                where u.id = ? and u.status = 'ACTIVE' and oh.payment_status = 'PAID'
                  and not exists (
                      select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = u.id
                  )
                """, JdbcResponseMapper.INSTANCE, memberId, AuthContext.currentMemberId()).stream()
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
        String name = request.name().trim().replaceAll("\\s+", " ");
        Integer duplicateCount = jdbcTemplate.queryForObject(
                "select count(*) from companies where lower(replace(name, ' ', '')) = lower(replace(?, ' ', ''))",
                Integer.class, name);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new IllegalArgumentException("이미 등록된 회사명입니다.");
        }
        jdbcTemplate.update("""
                insert into companies (name, created_at, updated_at)
                values (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, name);
        Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        return jdbcTemplate.queryForObject("""
                select c.id, c.name, c.work_zipcode, c.work_address1, c.work_address2,
                       c.description, c.industry_id, i.name as industry_name
                from companies c left join industries i on i.id = c.industry_id where c.id = ?
                """, JdbcResponseMapper.INSTANCE, id);
    }

    @PostMapping("/hobbies")
    @Transactional
    public Map<String, Object> createHobby(@Valid @RequestBody HobbyCreateRequest request) {
        String name = request.name().trim().replaceAll("\\s+", " ");
        Integer duplicateCount = jdbcTemplate.queryForObject("""
                select count(*) from hobbies
                where lower(replace(name, ' ', '')) = lower(replace(?, ' ', ''))
                """, Integer.class, name);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new IllegalArgumentException("이미 등록된 취미입니다.");
        }
        try {
            jdbcTemplate.update("""
                    insert into hobbies (name, created_at, updated_at)
                    values (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, name);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("이미 등록된 취미입니다.");
        }
        Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("name", name);
        response.put("memberCount", 0);
        return response;
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
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        if (StringUtils.hasText(category)) {
            return jdbcTemplate.query("""
                    select c.id, c.name, c.description, c.category,
                           case when exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = u.id)
                                then '차단한 사용자' else u.name end as president_name,
                           case when manager_block.blocked_id is not null
                                then '차단한 사용자' else manager.name end as secretary_name,
                           count(case when blocked.blocked_id is null then cm.id end) as member_count
                    from clubs c
                    left join users u on u.id = c.president_user_id
                    left join users manager on manager.id = coalesce(
                           c.manager_user_id,
                           (
                               select min(legacy_manager.user_id)
                               from club_members legacy_manager
                               where legacy_manager.club_id = c.id
                                 and legacy_manager.club_role = 'MANAGER'
                                 and legacy_manager.left_at is null
                                 and legacy_manager.user_id <> c.president_user_id
                           )
                    )
                    left join user_blocks manager_block on manager_block.blocker_id = ? and manager_block.blocked_id = manager.id
                    left join club_members cm on cm.club_id = c.id and cm.left_at is null
                    left join user_blocks blocked on blocked.blocker_id = ? and blocked.blocked_id = cm.user_id
                    where c.category = ?
                    group by c.id, c.name, c.description, c.category, u.name, manager.name, manager_block.blocked_id
                    order by c.id desc
                    """, JdbcResponseMapper.INSTANCE, currentUserId, currentUserId, currentUserId,
                    category.toUpperCase(Locale.ROOT));
        }

        return jdbcTemplate.query("""
                select c.id, c.name, c.description, c.category,
                       case when exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = u.id)
                            then '차단한 사용자' else u.name end as president_name,
                       case when manager_block.blocked_id is not null
                            then '차단한 사용자' else manager.name end as secretary_name,
                       count(case when blocked.blocked_id is null then cm.id end) as member_count
                from clubs c
                left join users u on u.id = c.president_user_id
                left join users manager on manager.id = coalesce(
                       c.manager_user_id,
                       (
                           select min(legacy_manager.user_id)
                           from club_members legacy_manager
                           where legacy_manager.club_id = c.id
                             and legacy_manager.club_role = 'MANAGER'
                             and legacy_manager.left_at is null
                             and legacy_manager.user_id <> c.president_user_id
                       )
                )
                left join user_blocks manager_block on manager_block.blocker_id = ? and manager_block.blocked_id = manager.id
                left join club_members cm on cm.club_id = c.id and cm.left_at is null
                left join user_blocks blocked on blocked.blocker_id = ? and blocked.blocked_id = cm.user_id
                group by c.id, c.name, c.description, c.category, u.name, manager.name, manager_block.blocked_id
                order by c.id desc
                """, JdbcResponseMapper.INSTANCE, currentUserId, currentUserId, currentUserId);
    }

    @PostMapping("/clubs")
    @Transactional
    public Map<String, Object> createClub(@Valid @RequestBody ClubCreateRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        String category = request.category().trim().toUpperCase(Locale.ROOT);
        if (!category.equals("HOBBY") && !category.equals("RESEARCH")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 동호회 유형입니다.");
        }

        Map<String, Object> clubValues = new LinkedHashMap<>();
        clubValues.put("name", request.name().trim());
        clubValues.put("description", blankToEmpty(request.description()));
        clubValues.put("category", category);
        clubValues.put("president_user_id", currentUserId);

        Number clubId = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("clubs")
                .usingColumns("name", "description", "category", "president_user_id")
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(clubValues);

        jdbcTemplate.update("""
                insert into club_members (club_id, user_id, club_role, joined_at)
                values (?, ?, 'PRESIDENT', CURRENT_TIMESTAMP)
                """, clubId.longValue(), currentUserId);

        return Map.of(
                "id", clubId.longValue(),
                "category", category,
                "presidentUserId", currentUserId);
    }

    @GetMapping("/clubs/{clubId}")
    public Map<String, Object> findClub(@PathVariable Long clubId) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        return jdbcTemplate.queryForObject("""
                select c.id, c.name, c.description, c.category,
                       case when president_block.blocked_id is not null then '차단한 사용자' else president.name end as president_name,
                       max(case when secretary_block.blocked_id is null then secretary.name else '차단한 사용자' end) as secretary_name,
                       count(distinct case when member_block.blocked_id is null then cm.id end) as member_count,
                       count(distinct case when post_block.blocked_id is null then p.id end) as post_count
                from clubs c
                left join users president on president.id = c.president_user_id
                left join user_blocks president_block on president_block.blocker_id = ? and president_block.blocked_id = president.id
                left join club_members cm on cm.club_id = c.id and cm.left_at is null
                left join user_blocks member_block on member_block.blocker_id = ? and member_block.blocked_id = cm.user_id
                left join users secretary on secretary.id = coalesce(
                       c.manager_user_id,
                       (
                           select min(legacy_manager.user_id)
                           from club_members legacy_manager
                           where legacy_manager.club_id = c.id
                             and legacy_manager.club_role = 'MANAGER'
                             and legacy_manager.left_at is null
                             and legacy_manager.user_id <> c.president_user_id
                       )
                )
                left join user_blocks secretary_block on secretary_block.blocker_id = ? and secretary_block.blocked_id = secretary.id
                left join posts p on p.club_id = c.id and p.post_kind = 'CLUB' and p.status = 'PUBLISHED'
                left join user_blocks post_block on post_block.blocker_id = ? and post_block.blocked_id = p.user_id
                where c.id = ?
                group by c.id, c.name, c.description, c.category, president.name, president_block.blocked_id
                """, JdbcResponseMapper.INSTANCE, currentUserId, currentUserId, currentUserId, currentUserId, clubId);
    }

    @GetMapping("/clubs/{clubId}/members")
    public List<Map<String, Object>> findClubMembers(@PathVariable Long clubId) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
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
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = u.id)
                order by case cm.club_role when 'PRESIDENT' then 1 when 'MANAGER' then 2 else 3 end, u.name
                """, JdbcResponseMapper.INSTANCE, clubId, currentUserId);
    }

    @GetMapping("/clubs/{clubId}/membership")
    public Map<String, Object> findClubMembership(@PathVariable Long clubId) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        if (currentUserId == null) {
            return Map.of(
                    "authenticated", false,
                    "isMember", false,
                    "applicationStatus", "",
                    "role", "",
                    "canManage", false,
                    "canAssignPresident", false,
                    "pendingCount", 0);
        }

        String role = findClubRole(clubId, currentUserId);
        String applicationStatus = jdbcTemplate.query("""
                select status
                from club_join_applications
                where club_id = ? and user_id = ?
                """, resultSet -> resultSet.next() ? resultSet.getString("status") : "", clubId, currentUserId);
        boolean canManage = "PRESIDENT".equals(role) || "MANAGER".equals(role);
        Integer pendingCount = canManage ? jdbcTemplate.queryForObject("""
                select count(*) from club_join_applications
                where club_id = ? and status = 'PENDING'
                """, Integer.class, clubId) : 0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticated", true);
        response.put("isMember", !role.isEmpty());
        response.put("applicationStatus", applicationStatus);
        response.put("role", role);
        response.put("canManage", canManage);
        response.put("canAssignPresident", "PRESIDENT".equals(role));
        response.put("pendingCount", pendingCount == null ? 0 : pendingCount);
        return response;
    }

    @PostMapping("/clubs/{clubId}/applications")
    @Transactional
    public Map<String, Object> applyToClub(@PathVariable Long clubId) {
        Long currentUserId = AuthContext.currentMemberId();
        lockClub(clubId);
        if (isActiveClubMember(clubId, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입한 동호회입니다.");
        }

        int updated = jdbcTemplate.update("""
                update club_join_applications
                set status = 'PENDING', applied_at = CURRENT_TIMESTAMP, reviewed_at = null,
                    reviewed_by_user_id = null, updated_at = CURRENT_TIMESTAMP
                where club_id = ? and user_id = ? and status <> 'PENDING'
                """, clubId, currentUserId);
        if (updated == 0) {
            try {
                jdbcTemplate.update("""
                        insert into club_join_applications (
                            club_id, user_id, status, applied_at, created_at, updated_at
                        ) values (?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, clubId, currentUserId);
            } catch (DuplicateKeyException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리 대기 중인 신청입니다.");
            }
        }
        return Map.of("status", "PENDING");
    }

    @GetMapping("/clubs/{clubId}/applications")
    public List<Map<String, Object>> findClubApplications(@PathVariable Long clubId) {
        Long currentUserId = AuthContext.currentMemberId();
        requireClubManager(clubId, currentUserId);
        return jdbcTemplate.query("""
                select a.id, a.status, a.applied_at, u.id as user_id, u.name,
                       u.admission_year, m.name as major_name, co.name as company_name, u.job_title
                from club_join_applications a
                join users u on u.id = a.user_id
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                where a.club_id = ? and a.status = 'PENDING'
                order by a.applied_at, a.id
                """, JdbcResponseMapper.INSTANCE, clubId);
    }

    @PatchMapping("/clubs/{clubId}/applications/{applicationId}")
    @Transactional
    public Map<String, Object> reviewClubApplication(
            @PathVariable Long clubId,
            @PathVariable Long applicationId,
            @Valid @RequestBody ClubApplicationReviewRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        lockClub(clubId);
        requireClubManager(clubId, currentUserId);
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!decision.equals("APPROVE") && !decision.equals("REJECT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "승인 또는 거절만 선택할 수 있습니다.");
        }

        List<Long> applicantIds = jdbcTemplate.queryForList("""
                select user_id from club_join_applications
                where id = ? and club_id = ? and status = 'PENDING'
                """, Long.class, applicationId, clubId);
        if (applicantIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "처리할 신청을 찾을 수 없습니다.");
        }
        Long applicantId = applicantIds.get(0);
        if (decision.equals("APPROVE")) {
            int reactivated = jdbcTemplate.update("""
                    update club_members
                    set club_role = 'MEMBER', joined_at = CURRENT_TIMESTAMP, left_at = null
                    where club_id = ? and user_id = ?
                    """, clubId, applicantId);
            if (reactivated == 0) {
                jdbcTemplate.update("""
                        insert into club_members (club_id, user_id, club_role, joined_at)
                        values (?, ?, 'MEMBER', CURRENT_TIMESTAMP)
                        """, clubId, applicantId);
            }
        }
        String status = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        jdbcTemplate.update("""
                update club_join_applications
                set status = ?, reviewed_at = CURRENT_TIMESTAMP, reviewed_by_user_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, status, currentUserId, applicationId);
        return Map.of("status", status);
    }

    @PutMapping("/clubs/{clubId}/leadership/{role}")
    @Transactional
    public Map<String, Object> assignClubLeadership(
            @PathVariable Long clubId,
            @PathVariable String role,
            @Valid @RequestBody ClubLeadershipRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        lockClub(clubId);
        String requestedRole = role.trim().toUpperCase(Locale.ROOT);
        String currentRole = findClubRole(clubId, currentUserId);
        if (requestedRole.equals("PRESIDENT") && !"PRESIDENT".equals(currentRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회장만 다음 회장을 지목할 수 있습니다.");
        }
        if (requestedRole.equals("MANAGER")
                && !"PRESIDENT".equals(currentRole)
                && !"MANAGER".equals(currentRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회장과 매니저만 다음 매니저를 지목할 수 있습니다.");
        }
        if (!requestedRole.equals("PRESIDENT") && !requestedRole.equals("MANAGER")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경할 수 없는 역할입니다.");
        }
        if (requestedRole.equals("PRESIDENT") && request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "회장은 반드시 동호회 회원 중 1명이어야 합니다.");
        }
        if (request.userId() != null && !isActiveClubMember(clubId, request.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동호회 회원만 지목할 수 있습니다.");
        }

        Map<String, Object> leaders = jdbcTemplate.queryForObject("""
                select president_user_id, manager_user_id from clubs where id = ?
                """, JdbcResponseMapper.INSTANCE, clubId);
        Long otherLeaderId = requestedRole.equals("PRESIDENT")
                ? nullableLong(leaders.get("managerUserId"))
                : nullableLong(leaders.get("presidentUserId"));
        if (request.userId() != null && request.userId().equals(otherLeaderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "회장과 매니저는 서로 다른 회원이어야 합니다.");
        }

        String column = requestedRole.equals("PRESIDENT") ? "president_user_id" : "manager_user_id";
        jdbcTemplate.update("update clubs set " + column + " = ?, updated_at = CURRENT_TIMESTAMP where id = ?",
                request.userId(), clubId);
        synchronizeClubRoles(clubId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("role", requestedRole);
        response.put("userId", request.userId());
        return response;
    }

    @GetMapping("/clubs/{clubId}/posts")
    public List<Map<String, Object>> findClubPosts(@PathVariable Long clubId) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        return jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자' else u.name end as author_name
                from posts p
                join users u on u.id = p.user_id
                where p.club_id = ? and p.post_kind = 'CLUB' and p.status = 'PUBLISHED'
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = p.user_id)
                order by p.id desc
                """, JdbcResponseMapper.INSTANCE, clubId, currentUserId);
    }

    @GetMapping("/clubs/{clubId}/posts/{postId}")
    public Map<String, Object> findClubPost(
            @PathVariable Long clubId,
            @PathVariable Long postId) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자' else u.name end as author_name
                from posts p
                join users u on u.id = p.user_id
                where p.id = ? and p.club_id = ? and p.post_kind = 'CLUB' and p.status = 'PUBLISHED'
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = p.user_id)
                """, JdbcResponseMapper.INSTANCE, postId, clubId, currentUserId);
        return requirePost(rows);
    }

    @GetMapping("/clubs/{clubId}/posting-permission")
    public Map<String, Object> findClubPostingPermission(@PathVariable Long clubId) {
        return Map.of("canPost", canManageClub(clubId, AuthContext.currentMemberId()));
    }

    @PostMapping("/clubs/{clubId}/posts")
    @Transactional
    public Map<String, Object> createClubPost(
            @PathVariable Long clubId,
            @Valid @RequestBody ClubPostCreateRequest request) {
        Long currentUserId = AuthContext.currentMemberId();
        if (!canManageClub(clubId, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회장과 매니저만 공지/뉴스를 작성할 수 있습니다.");
        }

        jdbcTemplate.update("""
                insert into posts (
                    user_id, title, body, thumbnail_url, status, post_kind, club_id, created_at, updated_at
                ) values (?, ?, ?, ?, 'PUBLISHED', 'CLUB', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, currentUserId, request.title().trim(), request.body(),
                MarkdownImageExtractor.firstImageUrl(request.body()), clubId);

        Long id = jdbcTemplate.queryForObject("select max(id) from posts", Long.class);
        return Map.of("id", id);
    }

    @GetMapping("/notices")
    public CursorPageResponse<Map<String, Object>> findNotices(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return findPosts("NOTICE", cursor, size, null);
    }

    @GetMapping("/notices/{id}")
    public Map<String, Object> findNotice(@PathVariable Long id) {
        return findPost("NOTICE", id);
    }

    @GetMapping("/news")
    public CursorPageResponse<Map<String, Object>> findNews(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return findPosts("NEWS", cursor, size, null);
    }

    @GetMapping("/news/{id}")
    public Map<String, Object> findNewsPost(@PathVariable Long id) {
        return findPost("NEWS", id);
    }

    @GetMapping("/official-posts/{id}")
    public Map<String, Object> findOfficialPost(@PathVariable Long id) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at, p.post_kind,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자'
                            else coalesce(u.name, a.name) end as author_name
                from posts p
                left join users u on u.id = p.user_id
                left join admins a on a.id = p.admin_id
                where p.id = ? and p.post_kind in ('NOTICE', 'NEWS') and p.status = 'PUBLISHED'
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = p.user_id)
                """, JdbcResponseMapper.INSTANCE, id, currentUserId);
        return requirePost(rows);
    }

    @GetMapping("/business-posts")
    public CursorPageResponse<Map<String, Object>> findBusinessPosts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long industryId) {
        return findPosts("BUSINESS", cursor, size, industryId);
    }

    @GetMapping("/business-posts/{id}")
    public Map<String, Object> findBusinessPost(@PathVariable Long id) {
        return findPost("BUSINESS", id);
    }

    @GetMapping("/posts/recent")
    public List<Map<String, Object>> findRecentPosts(@RequestParam(required = false) Integer size) {
        int limit = Math.min(Math.max(size == null ? 5 : size, 1), 20);
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        return jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at, p.post_kind,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자'
                            else coalesce(u.name, a.name) end as author_name,
                       p.industry_id, i.name as industry_name,
                       p.club_id, c.name as club_name, c.category as club_category
                from posts p
                left join users u on u.id = p.user_id
                left join admins a on a.id = p.admin_id
                left join industries i on i.id = p.industry_id
                left join clubs c on c.id = p.club_id
                where p.status = 'PUBLISHED'
                  and p.post_kind in ('NOTICE', 'NEWS', 'CLUB', 'BUSINESS')
                  and (p.post_kind <> 'CLUB' or p.club_id is not null)
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = p.user_id)
                order by p.created_at desc, p.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE, currentUserId, limit);
    }

    @PostMapping("/business-posts")
    @Transactional
    public Map<String, Object> createBusinessPost(@Valid @RequestBody BusinessPostCreateRequest request) {
        Integer industryCount = jdbcTemplate.queryForObject(
                "select count(*) from industries where id = ?", Integer.class, request.industryId());
        if (industryCount == null || industryCount == 0) {
            throw new IllegalArgumentException("존재하지 않는 분야입니다.");
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("user_id", AuthContext.currentMemberId());
        values.put("title", request.title().trim());
        values.put("body", request.body());
        values.put("thumbnail_url", MarkdownImageExtractor.firstImageUrl(request.body()));
        values.put("status", "PUBLISHED");
        values.put("post_kind", "BUSINESS");
        values.put("industry_id", request.industryId());

        Number id = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("posts")
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(values);
        return Map.of("id", id.longValue());
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
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자'
                            else coalesce(u.name, a.name) end as author_name,
                       p.industry_id, i.name as industry_name
                from posts p
                left join users u on u.id = p.user_id
                left join admins a on a.id = p.admin_id
                left join industries i on i.id = p.industry_id
                where p.status = 'PUBLISHED'
                  and p.post_kind = ?
                  and (? is null or p.id < ?)
                  and (? is null or p.industry_id = ?)
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = p.user_id)
                order by p.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE, kind, cursor, cursor, industryId, industryId,
                currentUserId, CursorPageFactory.queryLimit(size));

        return CursorPageFactory.from(rows, size);
    }

    private Map<String, Object> findPost(String kind, Long id) {
        Long currentUserId = AuthContext.currentMemberIdOrNull();
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.created_at,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자'
                            else coalesce(u.name, a.name) end as author_name,
                       p.industry_id, i.name as industry_name
                from posts p
                left join users u on u.id = p.user_id
                left join admins a on a.id = p.admin_id
                left join industries i on i.id = p.industry_id
                where p.id = ? and p.post_kind = ? and p.status = 'PUBLISHED'
                  and not exists (select 1 from user_blocks ub where ub.blocker_id = ? and ub.blocked_id = p.user_id)
                """, JdbcResponseMapper.INSTANCE, id, kind, currentUserId);
        return requirePost(rows);
    }

    private Map<String, Object> requirePost(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private boolean canManageClub(Long clubId, Long userId) {
        String role = findClubRole(clubId, userId);
        return "PRESIDENT".equals(role) || "MANAGER".equals(role);
    }

    private void requireClubManager(Long clubId, Long userId) {
        if (!canManageClub(clubId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회장과 매니저만 관리할 수 있습니다.");
        }
    }

    private void lockClub(Long clubId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "select id from clubs where id = ? for update", Long.class, clubId);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "동호회를 찾을 수 없습니다.");
        }
    }

    private boolean isActiveClubMember(Long clubId, Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from club_members
                where club_id = ? and user_id = ? and left_at is null
                """, Integer.class, clubId, userId);
        return count != null && count > 0;
    }

    private String findClubRole(Long clubId, Long userId) {
        List<String> roles = jdbcTemplate.queryForList("""
                select case
                           when c.president_user_id = ? then 'PRESIDENT'
                           when c.manager_user_id = ? then 'MANAGER'
                           when c.manager_user_id is null
                                and cm.club_role = 'MANAGER'
                                and cm.user_id = (
                                    select min(legacy_manager.user_id)
                                    from club_members legacy_manager
                                    where legacy_manager.club_id = c.id
                                      and legacy_manager.club_role = 'MANAGER'
                                      and legacy_manager.left_at is null
                                )
                           then 'MANAGER'
                           when cm.id is not null then 'MEMBER'
                           else ''
                       end
                from clubs c
                left join club_members cm on cm.club_id = c.id and cm.user_id = ? and cm.left_at is null
                where c.id = ?
                """, String.class, userId, userId, userId, clubId);
        return roles.isEmpty() ? "" : roles.get(0);
    }

    private void synchronizeClubRoles(Long clubId) {
        jdbcTemplate.update("""
                update club_members
                set club_role = 'MEMBER'
                where club_id = ? and left_at is null
                """, clubId);
        jdbcTemplate.update("""
                update club_members
                set club_role = 'PRESIDENT'
                where club_id = ? and left_at is null
                  and user_id = (select president_user_id from clubs where id = ?)
                """, clubId, clubId);
        jdbcTemplate.update("""
                update club_members
                set club_role = 'MANAGER'
                where club_id = ? and left_at is null
                  and user_id = (select manager_user_id from clubs where id = ?)
                """, clubId, clubId);
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
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
            @Size(max = 50) String homeZipcode,
            String homeAddress1,
            String homeAddress2,
            boolean homeAddressPublic,
            Long companyId,
            Long industryId,
            @Size(max = 50) String workZipcode,
            @Size(max = 255) String workAddress1,
            @Size(max = 255) String workAddress2,
            @Size(max = 255) String jobTitle,
            List<Long> hobbyIds,
            List<@Size(max = 1000) String> webLinks,
            @Size(max = 500) String prText) {
    }

    public record PreferenceUpdateRequest(
            boolean notificationEnabled,
            boolean phonePublic,
            boolean emailPublic,
            boolean homeAddressPublic) {
    }

    public record CompanyCreateRequest(
            @NotBlank @Size(max = 255) String name) {
    }

    public record HobbyCreateRequest(
            @NotBlank @Size(max = 50) String name) {
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

    public record ClubPostCreateRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank String body) {
    }

    public record ClubCreateRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 2000) String description,
            @NotBlank String category) {
    }

    public record ClubApplicationReviewRequest(
            @NotBlank String decision) {
    }

    public record ClubLeadershipRequest(
            Long userId) {
    }

    public record BusinessPostCreateRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank String body,
            @NotNull Long industryId) {
    }
}
