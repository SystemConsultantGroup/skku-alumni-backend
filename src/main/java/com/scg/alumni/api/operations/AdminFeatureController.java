package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.api.common.MarkdownImageExtractor;
import com.scg.alumni.global.security.AdminRoleGuard;
import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.infrastructure.push.PushNotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin")
public class AdminFeatureController {

    private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbcTemplate;
    private final PushNotificationService pushNotificationService;
    private final AdminRoleGuard adminRoleGuard;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/dashboard")
    public Map<String, Object> findDashboard() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stats", Map.of(
                "activeMembers", count("select count(*) from users where status = 'ACTIVE'"),
                "pendingMembers", count("select count(*) from users where status = 'PENDING'"),
                "withdrawnMembers", count("select count(*) from users where status = 'WITHDRAWN'"),
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
                select u.id, u.name, u.kingo_id as user_login_id, u.student_id, u.phone, u.email, u.status,
                       m.name as major_name, co.name as company_name, u.job_title,
                       ot.generation, ot.phase, orole.name as officer_role_name,
                       coalesce(oh.payment_status, pr.status) as payment_status
                from users u
                join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id
                left join officer_terms ot on ot.current_term = true
                left join officer_histories oh on oh.user_id = u.id and oh.officer_term_id = ot.id
                left join officer_roles orole on orole.id = oh.officer_role_id
                left join payment_records pr on pr.user_id = u.id and pr.officer_term_id = ot.id
                where (? is null or u.id < ?)
                  and (? is null or lower(u.name) like ? or lower(m.name) like ? or lower(coalesce(co.name, '')) like ?)
                  and (? is null or coalesce(oh.payment_status, pr.status) = ?)
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
        if ("ACTIVE".equals(status) && !hasPaidCurrentOfficerFee(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현행 임기 회비 납부 확인 후 활성화할 수 있습니다.");
        }
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
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedKeyword = normalizeLike(keyword);
        String normalizedStatus = normalizeUpper(status);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select id, name, student_id, phone, email, major_name, admission_year, desired_role,
                       company_name, job_title, status, reviewed_at, created_at
                from member_applications
                where (? is null or id < ?)
                  and (
                      ? is null
                      or lower(coalesce(name, '')) like ?
                      or lower(coalesce(student_id, '')) like ?
                      or lower(coalesce(phone, '')) like ?
                      or lower(coalesce(email, '')) like ?
                      or lower(coalesce(major_name, '')) like ?
                      or lower(coalesce(desired_role, '')) like ?
                      or lower(coalesce(company_name, '')) like ?
                      or lower(coalesce(job_title, '')) like ?
                  )
                  and (? is null or status = ?)
                order by id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor,
                normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedStatus, normalizedStatus, CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PatchMapping("/applications/{id}/status")
    @Transactional
    public Map<String, Object> updateApplicationStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        String status = normalizeRequiredStatus(request.status(), "PENDING", "APPROVED", "REJECTED");
        if ("APPROVED".equals(status)) {
            Long userId = approveApplication(id);
            audit("APPROVE_APPLICATION", "member_application", id);
            return Map.of("id", id, "status", status, "userId", userId);
        }

        jdbcTemplate.update("""
                update member_applications
                set status = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, status, AuthContext.currentAdminId(), id);
        audit("UPDATE_APPLICATION_STATUS", "member_application", id);
        return Map.of("id", id, "status", status);
    }

    @GetMapping("/payments")
    public CursorPageResponse<Map<String, Object>> findPayments(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long officerTermId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedKeyword = normalizeLike(keyword);
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
                  and (
                      ? is null
                      or lower(coalesce(u.name, '')) like ?
                      or lower(coalesce(u.student_id, '')) like ?
                      or lower(coalesce(orole.name, '')) like ?
                  )
                  and (? is null or pr.status = ?)
                  and (? is null or pr.officer_term_id = ?)
                order by pr.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor,
                normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedStatus, normalizedStatus, officerTermId, officerTermId,
                CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    /**
     * 최근 추이. 수치 하나로는 늘고 있는지 줄고 있는지 알 수 없다.
     *
     * <p>회의에서 "날짜별로 몇 명이 들어왔느냐가 지금은 없다"고 나온 부분이다.
     * 가입이 없는 날도 0으로 채워 보낸다. 빠진 날짜를 화면이 메우게 하면
     * 그래프마다 같은 일을 반복해야 한다.
     */
    @GetMapping("/dashboard/trends")
    public List<Map<String, Object>> findDashboardTrends(
            @RequestParam(required = false, defaultValue = "30") int days
    ) {
        int range = Math.min(Math.max(days, 7), 180);
        Map<String, Integer> signups = countByDay("select date(created_at) as day, count(*) as total from users where created_at >= ? group by day", range);
        Map<String, Integer> payments = countByDay("select date(paid_at) as day, count(*) as total from payment_records where status = 'PAID' and paid_at >= ? group by day", range);

        LocalDate today = LocalDate.now(SEOUL);
        List<Map<String, Object>> trends = new ArrayList<>();
        for (int offset = range - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            String key = day.toString();
            trends.add(Map.of(
                    "date", key,
                    "signups", signups.getOrDefault(key, 0),
                    "payments", payments.getOrDefault(key, 0)));
        }
        return trends;
    }

    private Map<String, Integer> countByDay(String sql, int days) {
        LocalDate from = LocalDate.now(SEOUL).minusDays(days - 1L);
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(sql, resultSet -> {
            counts.put(resultSet.getDate("day").toLocalDate().toString(), resultSet.getInt("total"));
        }, from);
        return counts;
    }

    @GetMapping("/officer-terms")
    public List<Map<String, Object>> findOfficerTerms() {
        return jdbcTemplate.query("""
                select id, generation, phase, started_at, ended_at, current_term
                from officer_terms
                order by generation desc, phase desc
                """, JdbcResponseMapper.INSTANCE);
    }

    /**
     * 특정 대·기의 회비 납부를 기록한다.
     *
     * <p>회원들은 임기를 "대·기"가 아니라 연도로 기억해서, 1기 기간 중에 2기분을
     * 미리 보내는 일이 흔하다. 그래서 현행 임기로 자동 생성된 레코드를 뒤집는
     * 것만으로는 부족하고, 사무처가 어느 대·기에 대한 납부인지 직접 지정할 수
     * 있어야 한다. 회의에서 정리된 요건이다.
     */
    @PostMapping("/payments")
    @Transactional
    public Map<String, Object> registerPayment(@Valid @RequestBody PaymentRegisterRequest request) {
        String status = normalizeRequiredStatus(request.status(), "PAID", "UNPAID");
        Map<String, Object> term = jdbcTemplate.query("""
                select id, generation, phase
                from officer_terms
                where id = ?
                """, JdbcResponseMapper.INSTANCE, request.officerTermId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 임기를 찾을 수 없습니다."));

        Long officerRoleId = request.officerRoleId() != null
                ? request.officerRoleId()
                : findLatestOfficerRoleId(request.userId());
        if (officerRoleId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "직책 이력이 없는 회원입니다. 직책을 함께 지정해주세요.");
        }
        String roleName = jdbcTemplate.queryForObject("select name from officer_roles where id = ?", String.class, officerRoleId);

        ensureCurrentOfficerHistory(request.userId(), request.officerTermId(), officerRoleId);
        // 공직자 부회장(50만원)처럼 예외가 있어 사무처가 금액을 지정할 수 있다.
        int amount = request.amount() != null ? request.amount() : paymentAmount(roleName);
        ensurePaymentRecord(request.userId(), request.officerTermId(), amount);
        jdbcTemplate.update("""
                update payment_records set amount = ?, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and officer_term_id = ?
                """, amount, request.userId(), request.officerTermId());

        jdbcTemplate.update("""
                update payment_records
                set status = ?, paid_at = case when ? = 'PAID' then CURRENT_TIMESTAMP else null end, updated_at = CURRENT_TIMESTAMP
                where user_id = ?
                  and officer_term_id = ?
                """, status, status, request.userId(), request.officerTermId());
        jdbcTemplate.update("""
                update officer_histories
                set payment_status = ?, updated_at = CURRENT_TIMESTAMP
                where user_id = ?
                  and officer_term_id = ?
                """, status, request.userId(), request.officerTermId());

        Long paymentId = jdbcTemplate.queryForObject("""
                select id from payment_records where user_id = ? and officer_term_id = ?
                """, Long.class, request.userId(), request.officerTermId());
        audit("REGISTER_PAYMENT", "payment_record", paymentId);
        return Map.of(
                "id", paymentId,
                "status", status,
                "generation", term.get("generation"),
                "phase", term.get("phase"));
    }

    @PatchMapping("/payments/{id}/status")
    @Transactional
    public Map<String, Object> updatePaymentStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        String status = normalizeRequiredStatus(request.status(), "PAID", "UNPAID");
        Map<String, Object> payment = jdbcTemplate.query("""
                select user_id, officer_term_id
                from payment_records
                where id = ?
                """, JdbcResponseMapper.INSTANCE, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회비 내역을 찾을 수 없습니다."));

        jdbcTemplate.update("""
                update payment_records
                set status = ?, paid_at = case when ? = 'PAID' then CURRENT_TIMESTAMP else null end, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, status, status, id);
        jdbcTemplate.update("""
                update officer_histories
                set payment_status = ?, updated_at = CURRENT_TIMESTAMP
                where user_id = ?
                  and officer_term_id = ?
                """, status, payment.get("userId"), payment.get("officerTermId"));
        audit("UPDATE_PAYMENT_STATUS", "payment_record", id);
        return Map.of("id", id, "status", status);
    }

    @GetMapping("/asis-sync")
    public CursorPageResponse<Map<String, Object>> findAsisSyncTargets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean synced,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedKeyword = normalizeLike(keyword);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select pcl.id, pcl.field_name, pcl.old_value, pcl.new_value, pcl.synced,
                       pcl.changed_at, pcl.synced_at, u.id as user_id, u.name, u.student_id
                from profile_change_logs pcl
                join users u on u.id = pcl.user_id
                where (? is null or pcl.id < ?)
                  and (
                      ? is null
                      or lower(coalesce(u.name, '')) like ?
                      or lower(coalesce(u.student_id, '')) like ?
                      or lower(coalesce(pcl.field_name, '')) like ?
                      or lower(coalesce(pcl.old_value, '')) like ?
                      or lower(coalesce(pcl.new_value, '')) like ?
                  )
                  and (? is null or pcl.synced = ?)
                order by pcl.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor,
                normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedKeyword, normalizedKeyword, normalizedKeyword,
                synced, synced, CursorPageFactory.queryLimit(size));
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

    @GetMapping("/report-posts")
    public CursorPageResponse<Map<String, Object>> findReportedPosts(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select ranked.cursor_id as id, ranked.*
                from (
                    select summary.*,
                           row_number() over (
                               order by summary.report_count desc, summary.latest_report_id desc
                           ) as cursor_id
                    from (
                        select p.id as target_post_id, p.title as target_title, p.status as post_status,
                               p.post_kind, max(r.id) as latest_report_id,
                               count(r.id) as report_count,
                               sum(case when r.status = 'PENDING' then 1 else 0 end) as pending_count,
                               max(r.created_at) as last_reported_at,
                               case when author.status = 'WITHDRAWN' then '탈퇴한 사용자'
                                    else coalesce(author.name, admin_author.name) end as author_name
                        from reports r
                        join posts p on p.id = r.target_post_id
                        left join users author on author.id = p.user_id
                        left join admins admin_author on admin_author.id = p.admin_id
                        where lower(r.target_type) = 'post'
                        group by p.id, p.title, p.status, p.post_kind,
                                 author.status, author.name, admin_author.name
                    ) summary
                ) ranked
                where (? is null or ranked.cursor_id > ?)
                order by ranked.cursor_id
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor, CursorPageFactory.queryLimit(size));
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
                select a.id, a.email, a.name, a.position, a.active, ar.id as role_id, ar.name as role_name, a.created_at
                from admins a
                join admin_roles ar on ar.id = a.admin_role_id
                order by a.id
                """, JdbcResponseMapper.INSTANCE);
    }

    @GetMapping("/manager-roles")
    public List<Map<String, Object>> findManagerRoles() {
        return jdbcTemplate.query("""
                select id, name, description
                from admin_roles
                order by id
                """, JdbcResponseMapper.INSTANCE);
    }

    /**
     * 운영자 계정을 발급한다.
     *
     * <p>직원마다 계정을 따로 줘야 누가 무엇을 처리했는지 감사 로그로 남는다.
     * 계정 공유를 막자는 것이 회의에서 나온 취지다.
     */
    @PostMapping("/managers")
    @Transactional
    public Map<String, Object> createManager(@Valid @RequestBody ManagerCreateRequest request) {
        adminRoleGuard.requireMaster();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Integer duplicated = jdbcTemplate.queryForObject("select count(*) from admins where email = ?", Integer.class, email);
        if (duplicated != null && duplicated > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 등록된 이메일입니다.");
        }
        requireExistingRole(request.adminRoleId());
        jdbcTemplate.update("""
                insert into admins (email, name, password, position, admin_role_id, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, email, request.name().trim(), passwordEncoder.encode(request.password()),
                text(request.position()), request.adminRoleId());
        Long id = jdbcTemplate.queryForObject("select id from admins where email = ?", Long.class, email);
        audit("CREATE_MANAGER", "admin", id);
        return Map.of("id", id, "email", email);
    }

    @PatchMapping("/managers/{id}")
    @Transactional
    public Map<String, Object> updateManager(@PathVariable Long id, @Valid @RequestBody ManagerUpdateRequest request) {
        adminRoleGuard.requireMaster();
        requireExistingManager(id);
        if (request.adminRoleId() != null) {
            requireExistingRole(request.adminRoleId());
            jdbcTemplate.update("update admins set admin_role_id = ?, updated_at = CURRENT_TIMESTAMP where id = ?",
                    request.adminRoleId(), id);
        }
        if (StringUtils.hasText(request.name())) {
            jdbcTemplate.update("update admins set name = ?, updated_at = CURRENT_TIMESTAMP where id = ?", request.name().trim(), id);
        }
        if (request.position() != null) {
            jdbcTemplate.update("update admins set position = ?, updated_at = CURRENT_TIMESTAMP where id = ?", text(request.position()), id);
        }
        if (StringUtils.hasText(request.password())) {
            jdbcTemplate.update("update admins set password = ?, updated_at = CURRENT_TIMESTAMP where id = ?",
                    passwordEncoder.encode(request.password()), id);
        }
        audit("UPDATE_MANAGER", "admin", id);
        return Map.of("id", id);
    }

    /** 권한 회수. 감사 로그의 주체로 남아 있어야 하므로 계정을 지우지 않고 비활성화한다. */
    @PatchMapping("/managers/{id}/active")
    @Transactional
    public Map<String, Object> updateManagerActive(@PathVariable Long id, @Valid @RequestBody ManagerActiveRequest request) {
        adminRoleGuard.requireMaster();
        requireExistingManager(id);
        if (!request.isActive() && id.equals(AuthContext.currentAdminId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 계정의 권한은 회수할 수 없습니다.");
        }
        if (!request.isActive() && isLastActiveMaster(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "마지막 사무총장 계정의 권한은 회수할 수 없습니다.");
        }
        jdbcTemplate.update("update admins set active = ?, updated_at = CURRENT_TIMESTAMP where id = ?", request.isActive(), id);
        audit(request.isActive() ? "ACTIVATE_MANAGER" : "DEACTIVATE_MANAGER", "admin", id);
        return Map.of("id", id, "active", request.isActive());
    }

    private void requireExistingManager(Long id) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from admins where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "운영자를 찾을 수 없습니다.");
        }
    }

    private void requireExistingRole(Long adminRoleId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from admin_roles where id = ?", Integer.class, adminRoleId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 권한입니다.");
        }
    }

    private boolean isLastActiveMaster(Long id) {
        Integer remaining = jdbcTemplate.queryForObject("""
                select count(*)
                from admins a
                join admin_roles ar on ar.id = a.admin_role_id
                where ar.name = 'MASTER' and a.active = true and a.id <> ?
                """, Integer.class, id);
        return remaining == null || remaining == 0;
    }

    @GetMapping("/posts")
    public CursorPageResponse<Map<String, Object>> findPosts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String postKind,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean editable,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedKeyword = normalizeLike(keyword);
        String normalizedPostKind = normalizeUpper(postKind);
        String normalizedStatus = normalizeUpper(status);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select p.id, p.title, p.body, p.thumbnail_url, p.post_kind, p.status, p.created_at, p.updated_at,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자'
                            else coalesce(u.name, a.name) end as author_name,
                       i.name as industry_name,
                       c.id as club_id, c.name as club_name, c.category as club_category,
                       count(r.id) as report_count
                from posts p
                left join users u on u.id = p.user_id
                left join admins a on a.id = p.admin_id
                left join industries i on i.id = p.industry_id
                left join clubs c on c.id = p.club_id
                left join reports r on r.target_post_id = p.id
                where (? is null or p.id < ?)
                  and (? is null or lower(p.title) like ? or lower(coalesce(p.body, '')) like ?
                       or lower(coalesce(u.name, a.name)) like ?)
                  and (? is null or p.post_kind = ?)
                  and (? is null or p.status = ?)
                  and (
                      ? is null
                      or (? = true and p.post_kind in ('NOTICE', 'NEWS'))
                      or (? = false and p.post_kind not in ('NOTICE', 'NEWS'))
                  )
                group by p.id, p.title, p.body, p.thumbnail_url, p.post_kind, p.status, p.created_at, p.updated_at,
                         u.name, u.status, a.name, i.name, c.id, c.name, c.category
                order by p.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor,
                normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                normalizedPostKind, normalizedPostKind,
                normalizedStatus, normalizedStatus,
                editable, editable, editable,
                CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    @PostMapping("/posts")
    @Transactional
    public Map<String, Object> createOfficialPost(@Valid @RequestBody OfficialPostWriteRequest request) {
        String postKind = normalizeRequiredStatus(request.postKind(), "NOTICE", "NEWS");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("admin_id", AuthContext.currentAdminId());
        values.put("title", request.title().trim());
        values.put("body", request.body());
        values.put("thumbnail_url", MarkdownImageExtractor.firstImageUrl(request.body()));
        values.put("status", "PUBLISHED");
        values.put("post_kind", postKind);

        // 넣을 컬럼을 명시하지 않으면 SimpleJdbcInsert가 테이블의 모든 컬럼을 대상으로
        // 삼아 값이 없는 자리에 NULL을 보낸다. created_at은 NOT NULL이고 기본값은
        // 컬럼을 생략했을 때만 적용되므로, 그대로 두면 공지 작성이 항상 실패한다.
        Number id = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("posts")
                .usingColumns(values.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(values);
        audit("CREATE_OFFICIAL_POST", "post", id.longValue());
        pushNotificationService.notifyOfficialPost(id.longValue(), postKind, request.title().trim());
        return Map.of("id", id.longValue(), "status", "PUBLISHED");
    }

    @PatchMapping("/posts/{id}")
    @Transactional
    public Map<String, Object> updateOfficialPost(
            @PathVariable Long id,
            @Valid @RequestBody OfficialPostWriteRequest request
    ) {
        String postKind = normalizeRequiredStatus(request.postKind(), "NOTICE", "NEWS");
        int updated = jdbcTemplate.update("""
                update posts
                set title = ?, body = ?, thumbnail_url = ?, post_kind = ?, updated_at = CURRENT_TIMESTAMP
                where id = ? and post_kind in ('NOTICE', 'NEWS')
                """, request.title().trim(), request.body(),
                MarkdownImageExtractor.firstImageUrl(request.body()), postKind, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "수정할 공지/뉴스를 찾을 수 없습니다.");
        }
        audit("UPDATE_OFFICIAL_POST", "post", id);
        return Map.of("id", id, "status", "UPDATED");
    }

    @PatchMapping("/posts/{id}/status")
    @Transactional
    public Map<String, Object> updatePostStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        String status = normalizeRequiredStatus(request.status(), "PUBLISHED", "HIDDEN");
        int updated = jdbcTemplate.update("""
                update posts
                set status = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, status, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        }
        audit("UPDATE_POST_STATUS", "post", id);
        return Map.of("id", id, "status", status);
    }

    @GetMapping("/posts/{id}/reports")
    public List<Map<String, Object>> findPostReports(@PathVariable Long id) {
        Integer postCount = jdbcTemplate.queryForObject(
                "select count(*) from posts where id = ?", Integer.class, id);
        if (postCount == null || postCount == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
        }

        return jdbcTemplate.query("""
                select r.id, r.reason, r.reason_others, r.status, r.admin_memo,
                       r.created_at, r.resolved_at,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자'
                            else coalesce(u.name, '알 수 없는 사용자') end as reporter_name
                from reports r
                left join users u on u.id = r.reporter_id
                where r.target_post_id = ?
                  and lower(r.target_type) = 'post'
                order by r.id desc
                """, JdbcResponseMapper.INSTANCE, id);
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

    private Long approveApplication(Long applicationId) {
        Map<String, Object> application = jdbcTemplate.query("""
                select id, name, student_id, phone, email, major_name, admission_year, desired_role,
                       company_name, job_title, status
                from member_applications
                where id = ?
                """, JdbcResponseMapper.INSTANCE, applicationId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "신청을 찾을 수 없습니다."));

        Long majorId = findMajorId(text(application.get("majorName")));
        Long officerRoleId = findOfficerRoleId(text(application.get("desiredRole")));
        Long officerTermId = findCurrentOfficerTermId();
        Long companyId = findOrCreateCompanyId(text(application.get("companyName")));
        Long userId = findOrCreateApplicationUser(application, majorId, companyId);

        ensureCurrentOfficerHistory(userId, officerTermId, officerRoleId);
        ensurePaymentRecord(userId, officerTermId, paymentAmount(text(application.get("desiredRole"))));

        jdbcTemplate.update("""
                update member_applications
                set status = 'APPROVED', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, AuthContext.currentAdminId(), applicationId);
        return userId;
    }

    private Long findOrCreateApplicationUser(Map<String, Object> application, Long majorId, Long companyId) {
        String studentId = text(application.get("studentId"));
        String phone = text(application.get("phone"));
        Integer admissionYear = number(application.get("admissionYear"));

        Long existingId = findExistingApplicationUser(text(application.get("name")), admissionYear, majorId, phone, studentId);
        if (existingId != null) {
            return existingId;
        }

        String storedStudentId = StringUtils.hasText(studentId) ? studentId : "APPLICATION-" + application.get("id");
        jdbcTemplate.update("""
                insert into users (
                    student_id, kingo_id, name, password, category, degree, major_id,
                    admission_year, phone, email, email_receive, company_id, job_title,
                    status, created_at, updated_at
                ) values (?, null, ?, '', 'UNDERGRADUATE', 'BACHELOR', ?, ?, ?, ?, true, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                storedStudentId, text(application.get("name")), majorId, admissionYear, phone,
                text(application.get("email")), companyId, text(application.get("jobTitle")));

        return jdbcTemplate.queryForObject("select id from users where student_id = ?", Long.class, storedStudentId);
    }

    private Long findExistingApplicationUser(String name, Integer admissionYear, Long majorId, String phone, String studentId) {
        if (StringUtils.hasText(studentId)) {
            Long byStudentId = jdbcTemplate.query("""
                    select id
                    from users
                    where student_id = ?
                    order by id
                    limit 1
                    """, (resultSet, rowNum) -> resultSet.getLong("id"), studentId)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (byStudentId != null) {
                return byStudentId;
            }
        }

        return jdbcTemplate.query("""
                select id
                from users
                where name = ?
                  and admission_year = ?
                  and major_id = ?
                  and replace(replace(replace(coalesce(phone, ''), '-', ''), ' ', ''), '.', '') = ?
                order by id
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"), name, admissionYear, majorId, normalizePhone(phone))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Long findMajorId(String majorName) {
        if (!StringUtils.hasText(majorName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "학과 정보가 필요합니다.");
        }
        return jdbcTemplate.query("""
                select coalesce(display_major_id, id) as id
                from majors
                where lower(replace(name, ' ', '')) = ?
                   or lower(replace(normalized_name, ' ', '')) = ?
                order by case when status = 'ACTIVE' then 0 else 1 end, id
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"), normalizeText(majorName), normalizeText(majorName))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 학과를 찾을 수 없습니다."));
    }

    private Long findOfficerRoleId(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "희망 직급이 필요합니다.");
        }
        return jdbcTemplate.query("""
                select id
                from officer_roles
                where lower(replace(name, ' ', '')) = ?
                order by id
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"), normalizeText(roleName))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 임원 직급을 찾을 수 없습니다."));
    }

    private Long findCurrentOfficerTermId() {
        return jdbcTemplate.query("""
                select id
                from officer_terms
                where current_term = true
                order by id desc
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "현행 임기를 찾을 수 없습니다."));
    }

    private Long findOrCreateCompanyId(String companyName) {
        if (!StringUtils.hasText(companyName)) {
            return null;
        }
        Long existingId = jdbcTemplate.query("""
                select id
                from companies
                where name = ?
                order by id
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"), companyName)
                .stream()
                .findFirst()
                .orElse(null);
        if (existingId != null) {
            return existingId;
        }
        jdbcTemplate.update("""
                insert into companies (name, created_at, updated_at)
                values (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, companyName);
        return jdbcTemplate.queryForObject("select id from companies where name = ?", Long.class, companyName);
    }

    private void ensureCurrentOfficerHistory(Long userId, Long officerTermId, Long officerRoleId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from officer_histories
                where user_id = ?
                  and officer_term_id = ?
                """, Integer.class, userId, officerTermId);
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update officer_histories
                    set officer_role_id = ?, updated_at = CURRENT_TIMESTAMP
                    where user_id = ?
                      and officer_term_id = ?
                    """, officerRoleId, userId, officerTermId);
            return;
        }
        jdbcTemplate.update("""
                insert into officer_histories (
                    user_id, officer_term_id, officer_role_id, started_at, payment_status, created_at, updated_at
                )
                select ?, id, ?, started_at, 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                from officer_terms
                where id = ?
                """, userId, officerRoleId, officerTermId);
    }

    private void ensurePaymentRecord(Long userId, Long officerTermId, int amount) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from payment_records
                where user_id = ?
                  and officer_term_id = ?
                """, Integer.class, userId, officerTermId);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into payment_records (
                    user_id, officer_term_id, amount, status, created_at, updated_at
                ) values (?, ?, ?, 'UNPAID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId, officerTermId, amount);
    }

    private boolean hasPaidCurrentOfficerFee(Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from officer_histories oh
                join officer_terms ot on ot.id = oh.officer_term_id
                left join payment_records pr on pr.user_id = oh.user_id and pr.officer_term_id = oh.officer_term_id
                where oh.user_id = ?
                  and ot.current_term = true
                  and (oh.payment_status = 'PAID' or pr.status = 'PAID')
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    /** 다른 임기의 납부를 기록할 때 쓸 직책. 가장 최근 임기의 직책을 그대로 이어 쓴다. */
    private Long findLatestOfficerRoleId(Long userId) {
        return jdbcTemplate.query("""
                select oh.officer_role_id
                from officer_histories oh
                join officer_terms ot on ot.id = oh.officer_term_id
                where oh.user_id = ?
                order by ot.generation desc, ot.phase desc
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong(1), userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 직책별 임원 기여금 기본값.
     *
     * <p>총동창회 회비안내의 금액표를 그대로 옮겼다. 지금까지는 회장 100만원,
     * 그 외 전부 30만원으로 계산해서 이사(10만원)와 감사(100만원)가 실제와
     * 달랐다.
     *
     * <p>부회장 중 공직자·교육자·은퇴동문은 50만원, 상임이사 중 공직자는
     * 20만원이고 고문은 자율이다. 이런 예외는 회원 정보만으로 판정할 수 없어
     * 사무처가 등록할 때 금액을 직접 지정한다.
     */
    private static final Map<String, Integer> OFFICER_DUES = Map.of(
            "회장", 100_000_000,
            "감사", 1_000_000,
            "부회장", 1_000_000,
            "자문위원", 300_000,
            "상임이사", 300_000,
            "이사", 100_000,
            "고문", 0);

    private int paymentAmount(String roleName) {
        Integer amount = OFFICER_DUES.get(roleName);
        if (amount == null) {
            // 표에 없는 직책이 생기면 사무처가 금액을 직접 넣도록 0으로 둔다.
            // 임의의 기본값을 넣으면 틀린 금액이 조용히 회비 내역에 남는다.
            return 0;
        }
        return amount;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private Integer number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && StringUtils.hasText(value.toString())) {
            return Integer.valueOf(value.toString());
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "입학년도가 필요합니다.");
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("[^0-9]", "");
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

    public record ManagerCreateRequest(
            @NotBlank @Email String email,
            @NotBlank String name,
            @NotBlank @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.") String password,
            String position,
            @NotNull Long adminRoleId
    ) {
    }

    public record ManagerUpdateRequest(
            String name,
            String position,
            @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.") String password,
            Long adminRoleId
    ) {
    }

    public record ManagerActiveRequest(
            @NotNull Boolean active
    ) {
        public boolean isActive() {
            return Boolean.TRUE.equals(active);
        }
    }

    public record PaymentRegisterRequest(
            @NotNull Long userId,
            @NotNull Long officerTermId,
            @NotBlank String status,
            Long officerRoleId,
            /** 비우면 직책별 기본 금액을 쓴다. 예외 금액일 때만 지정한다. */
            Integer amount
    ) {
    }

    public record OfficialPostWriteRequest(
            @NotBlank String postKind,
            @NotBlank String title,
            @NotBlank String body
    ) {
    }

    public record ReportStatusUpdateRequest(
            @NotBlank String status,
            String adminMemo
    ) {
    }
}
