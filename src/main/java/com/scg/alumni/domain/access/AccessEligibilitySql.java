package com.scg.alumni.domain.access;

/** SQL equivalent of whether an account has any current access, for bulk recipient selection. */
public final class AccessEligibilitySql {
    private AccessEligibilitySql() { }

    public static String forUser(String alias, int graceDays) {
        if (!alias.equals("u") || graceDays < 0) throw new IllegalArgumentException("Invalid eligibility predicate");
        return """
                (u.deleted_at is null and u.account_blocked = false and u.status <> 'WITHDRAWN'
                 and (exists (select 1 from officer_histories ah join officer_terms term_access on term_access.id = ah.officer_term_id
                              join officer_history_approvals aa on aa.officer_history_id = ah.id
                              where ah.user_id = u.id and ah.deleted_at is null and term_access.started_at <= CURRENT_DATE
                                and term_access.ended_at >= TIMESTAMPADD(DAY, -%d, CURRENT_DATE)
                                and ah.started_at <= CURRENT_DATE and (ah.ended_at is null or ah.ended_at >= CURRENT_DATE
                                     or (ah.ended_at = term_access.ended_at and term_access.ended_at >= TIMESTAMPADD(DAY, -%d, CURRENT_DATE))))
                      or exists (select 1 from alumni_organization_officers ao join alumni_organizations ag on ag.id = ao.organization_id
                                 where ao.user_id = u.id and ao.president = true and ao.ended_at_recorded_at is null
                                   and ao.effective_started_at <= CURRENT_DATE and (ao.ended_at is null or ao.ended_at >= CURRENT_DATE)
                                   and ag.dissolved_at is null)
                      or exists (select 1 from vip_qualifications v where v.user_id = u.id and v.revoked_at is null)))
                """.formatted(graceDays, graceDays);
    }
}
