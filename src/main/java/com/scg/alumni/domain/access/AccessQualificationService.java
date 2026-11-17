package com.scg.alumni.domain.access;

import java.time.LocalDate;
import java.util.List;
import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes eligibility from persistent state. It does not authenticate callers. */
@Service
@RequiredArgsConstructor
public class AccessQualificationService {
    private final JdbcTemplate jdbc;
    private final OfficerTermPolicyProperties policy;

    @Transactional(readOnly = true)
    public AccessLevel currentLevel(long userId) {
        return levelOn(userId, LocalDate.now(policy.zone()));
    }

    @Transactional(readOnly = true)
    public AccessLevel levelAt(long userId, LocalDate date) {
        return levelOn(userId, date);
    }

    @Transactional(readOnly = true)
    public boolean ownsProfileImage(long userId, String path) {
        Integer matches = jdbc.queryForObject("select count(*) from users where id = ? and deleted_at is null and profile_image_url = ?",
                Integer.class, userId, path);
        return matches != null && matches > 0;
    }

    public record CentralTerm(Long id, int generation, int phase, LocalDate startedAt, LocalDate endedAt,
                              String paymentStatus, boolean graceApplied) { }
    public record Presidency(Long organizationId, String organizationName, String title,
                             LocalDate startedAt, LocalDate endedAt) { }
    public record QualificationDetails(AccessLevel level, boolean enabled, CentralTerm centralTerm,
                                       List<Presidency> presidencies, boolean vip, int graceDays, String reason) { }

    @Transactional(readOnly = true)
    public QualificationDetails currentDetails(long userId) {
        return detailsOn(userId, LocalDate.now(policy.zone()));
    }

    AccessLevel levelOn(long userId, LocalDate today) {
        return detailsOn(userId, today).level();
    }

    QualificationDetails detailsOn(long userId, LocalDate today) {
        Integer enabled = jdbc.queryForObject("""
                select count(*) from users where id = ? and deleted_at is null and account_blocked = false
                  and status <> 'WITHDRAWN'
                """, Integer.class, userId);
        List<CentralTerm> terms = jdbc.query("""
                select ot.id, ot.generation, ot.phase, ot.started_at, ot.ended_at,
                       coalesce(pr.status, 'UNPAID') as payment_status
                from officer_histories oh join officer_terms ot on ot.id = oh.officer_term_id
                left join payment_records pr on pr.user_id = oh.user_id and pr.officer_term_id = ot.id and pr.deleted_at is null
                where oh.user_id = ? and oh.deleted_at is null and ot.started_at <= ? and ot.ended_at >= ?
                  and exists (select 1 from officer_history_approvals a where a.officer_history_id = oh.id)
                  and oh.started_at <= ? and (oh.ended_at is null or oh.ended_at >= ?
                      or (oh.ended_at = ot.ended_at and ot.ended_at >= ?))
                order by ot.started_at desc, ot.id desc limit 1
                """, (rs, row) -> new CentralTerm(rs.getLong("id"), rs.getInt("generation"), rs.getInt("phase"),
                rs.getObject("started_at", LocalDate.class), rs.getObject("ended_at", LocalDate.class),
                rs.getString("payment_status"), rs.getObject("ended_at", LocalDate.class).isBefore(today)),
                userId, today, today.minusDays(policy.accessGraceDays()), today, today, today.minusDays(policy.accessGraceDays()));
        List<Presidency> presidencies = jdbc.query("""
                select a.id, a.name, o.title, o.effective_started_at, o.ended_at
                from alumni_organization_officers o join alumni_organizations a on a.id = o.organization_id
                where o.user_id = ? and o.president = true and o.ended_at_recorded_at is null
                  and (o.ended_at is null or o.ended_at >= ?) and o.effective_started_at <= ? and a.dissolved_at is null
                order by a.id, o.id
                """, (rs, row) -> new Presidency(rs.getLong("id"), rs.getString("name"), rs.getString("title"),
                rs.getObject("effective_started_at", LocalDate.class), rs.getObject("ended_at", LocalDate.class)), userId, today, today);
        Integer vipCount = jdbc.queryForObject("select count(*) from vip_qualifications where user_id = ? and revoked_at is null", Integer.class, userId);
        boolean vip = vipCount != null && vipCount > 0;
        CentralTerm term = terms.isEmpty() ? null : terms.getFirst();
        boolean active = enabled != null && enabled > 0;
        AccessLevel level;
        String reason;
        if (!active) { level = AccessLevel.NONE; reason = "ACCOUNT_UNAVAILABLE"; }
        else if (term != null) { level = "PAID".equals(term.paymentStatus()) ? AccessLevel.FULL : AccessLevel.LIMITED; reason = "PAID".equals(term.paymentStatus()) ? "CENTRAL_PAID" : "CENTRAL_UNPAID"; }
        else if (!presidencies.isEmpty() || vip) { level = AccessLevel.LIMITED; reason = !presidencies.isEmpty() && vip ? "PRESIDENT_AND_VIP" : !presidencies.isEmpty() ? "PRESIDENT" : "VIP"; }
        else { level = AccessLevel.NONE; reason = "NO_QUALIFICATION"; }
        return new QualificationDetails(level, active, term, presidencies, vip, policy.accessGraceDays(), reason);
    }
}
