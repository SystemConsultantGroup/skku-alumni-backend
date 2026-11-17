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

    AccessLevel levelOn(long userId, LocalDate today) {
        Integer enabled = jdbc.queryForObject("""
                select count(*) from users where id = ? and deleted_at is null and account_blocked = false
                  and status <> 'WITHDRAWN'
                """, Integer.class, userId);
        if (enabled == null || enabled == 0) return AccessLevel.NONE;
        List<String> payments = jdbc.query("""
                select coalesce(pr.status, 'UNPAID') as payment_status
                from officer_histories oh join officer_terms ot on ot.id = oh.officer_term_id
                left join payment_records pr on pr.user_id = oh.user_id and pr.officer_term_id = ot.id and pr.deleted_at is null
                where oh.user_id = ? and oh.deleted_at is null and ot.started_at <= ? and ot.ended_at >= ?
                  and exists (select 1 from officer_history_approvals a where a.officer_history_id = oh.id)
                  and oh.started_at <= ? and (oh.ended_at is null or oh.ended_at >= ?
                      or (oh.ended_at = ot.ended_at and ot.ended_at >= ?))
                order by ot.started_at desc, ot.id desc limit 1
                """, (rs, row) -> rs.getString("payment_status"), userId, today, today.minusDays(policy.accessGraceDays()), today,
                today, today.minusDays(policy.accessGraceDays()));
        if (!payments.isEmpty()) return "PAID".equals(payments.get(0)) ? AccessLevel.FULL : AccessLevel.LIMITED;
        Integer otherQualifications = jdbc.queryForObject("""
                select (select count(*) from alumni_organization_officers o
                        join alumni_organizations a on a.id = o.organization_id
                        where o.user_id = ? and o.president = true and o.ended_at_recorded_at is null
                          and (o.ended_at is null or o.ended_at >= ?)
                          and o.effective_started_at <= ? and a.dissolved_at is null)
                     + (select count(*) from vip_qualifications v where v.user_id = ? and v.revoked_at is null)
                """, Integer.class, userId, today, today, userId);
        return otherQualifications != null && otherQualifications > 0 ? AccessLevel.LIMITED : AccessLevel.NONE;
    }
}
