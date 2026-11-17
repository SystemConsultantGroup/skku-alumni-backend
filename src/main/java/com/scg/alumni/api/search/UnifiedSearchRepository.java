package com.scg.alumni.api.search;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Repository;

@Repository
@Slf4j
@RequiredArgsConstructor
public class UnifiedSearchRepository {
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public List<Map<String, Object>> posts(String keyword, long viewer, Long cursor, int limit) {
        return posts(keyword, viewer, cursor, null, limit);
    }

    public List<Map<String, Object>> posts(String keyword, long viewer, Long cursor, Integer cursorRank, int limit) {
        return searchPosts(keyword, viewer, cursor, cursorRank, limit, false);
    }

    public List<Map<String, Object>> notices(String keyword, long viewer, Long cursor, Integer cursorRank, int limit) {
        return searchPosts(keyword, viewer, cursor, cursorRank, limit, true);
    }

    private List<Map<String, Object>> searchPosts(String keyword, long viewer, Long cursor, Integer cursorRank, int limit, boolean notice) {
        boolean ngram = environment.getProperty("search.mysql-ngram-enabled", Boolean.class, false)
                && keyword.codePointCount(0, keyword.length()) >= 2;
        return searchPosts(keyword, viewer, cursor, cursorRank, limit, notice, ngram);
    }

    private List<Map<String, Object>> searchPosts(String keyword, long viewer, Long cursor, Integer cursorRank, int limit, boolean notice, boolean ngram) {
        String pattern = "%" + keyword.toLowerCase(java.util.Locale.ROOT) + "%";
        if (cursor != null && cursorRank == null) {
            cursorRank = jdbc.query("""
                    select case when lower(p.title) like ? then 3
                                when lower(coalesce(u.name, a.name, '')) like ? then 2 else 1 end
                    from posts p left join users u on u.id = p.user_id and u.deleted_at is null
                    left join admins a on a.id = p.admin_id where p.id = ?
                    """, (rs, row) -> rs.getInt(1), pattern, pattern, cursor).stream().findFirst().orElse(1);
        }
        String predicate = ngram
                ? "(match(p.title, p.body) against (? in natural language mode) or lower(p.title) like ? or lower(coalesce(p.body, '')) like ? or lower(coalesce(u.name, a.name, '')) like ?)"
                : "(lower(p.title) like ? or lower(coalesce(p.body, '')) like ? or lower(coalesce(u.name, a.name, '')) like ?)";
        String kinds = notice ? "p.post_kind = 'NOTICE'" : "p.post_kind in ('COMMUNITY', 'CLUB', 'BUSINESS')";
        String sql = """
                select * from (select p.id, p.post_kind as postKind, p.club_id as clubId, c.category as clubCategory, p.title, p.body,
                       substring(coalesce(p.body, ''), 1, 200) as summary, p.created_at as createdAt,
                       case when lower(p.title) like ? then 3
                            when lower(coalesce(u.name, a.name, '')) like ? then 2 else 1 end as relevance,
                       case when u.status = 'WITHDRAWN' then '탈퇴한 사용자' else coalesce(u.name, a.name) end as authorName
                from posts p left join clubs c on c.id = p.club_id left join users u on u.id = p.user_id and u.deleted_at is null
                left join admins a on a.id = p.admin_id
                where p.deleted_at is null and p.status = 'PUBLISHED' and %s
                  and (p.post_kind <> 'CLUB' or (p.club_id is not null and u.id is not null))
                  and not exists (select 1 from user_blocks b where b.blocker_id = ? and b.blocked_id = p.user_id and b.deleted_at is null)
                  and %s
                ) results where (? is null or (? is null and id < ?) or relevance < ? or (relevance = ? and id < ?))
                order by relevance desc, id desc limit ?
                """.formatted(kinds, predicate);
        List<Object> args = new ArrayList<>(java.util.Arrays.asList(pattern, pattern, viewer));
        if (ngram) args.add(keyword);
        args.add(pattern); args.add(pattern); args.add(pattern);
        args.addAll(java.util.Arrays.asList(cursor, cursorRank, cursor, cursorRank, cursorRank, cursor, limit));
        try {
            return jdbc.queryForList(sql, args.toArray());
        } catch (DataAccessException failure) {
            if (ngram && missingFulltextIndex(failure)) {
                log.warn("Fulltext index for posts(title, body) is missing; falling back to LIKE search");
                return searchPosts(keyword, viewer, cursor, cursorRank, limit, notice, false);
            }
            throw failure;
        }
    }

    private boolean missingFulltextIndex(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getErrorCode() == 1191) return true;
        }
        return false;
    }

    public List<Map<String, Object>> people(String keyword, long viewer, LocalDate today, Integer cursorGroup, Long cursorId, int limit) {
        String pattern = "%" + keyword.toLowerCase(java.util.Locale.ROOT) + "%";
        List<Map<String, Object>> people = jdbc.queryForList("""
                select * from (select u.id, u.name,
                    case when exists (select 1 from officer_histories h join officer_terms t on t.id = h.officer_term_id
                        where h.user_id = u.id and h.deleted_at is null and h.started_at <= ?
                          and exists (select 1 from officer_history_approvals approval where approval.officer_history_id = h.id)
                          and (h.ended_at is null or h.ended_at >= ?) and t.started_at <= ? and t.ended_at >= ?)
                    then 1 else 0 end as officer
                from users u where u.deleted_at is null and u.status <> 'WITHDRAWN' and u.account_blocked = false
                  and lower(u.name) like ?
                  and not exists (select 1 from user_blocks b where b.blocker_id = ? and b.blocked_id = u.id and b.deleted_at is null)
                  and (exists (select 1 from officer_histories h join officer_terms t on t.id = h.officer_term_id
                        where h.user_id = u.id and h.deleted_at is null and h.started_at <= ?
                          and exists (select 1 from officer_history_approvals approval where approval.officer_history_id = h.id)
                          and (h.ended_at is null or h.ended_at >= ?) and t.started_at <= ? and t.ended_at >= ?)
                       or exists (select 1 from alumni_organization_officers o join alumni_organizations a on a.id = o.organization_id
                        where o.user_id = u.id and o.ended_at_recorded_at is null and o.effective_started_at <= ?
                          and (o.ended_at is null or o.ended_at >= ?) and a.dissolved_at is null))
                ) result where (? is null or officer < ? or (officer = ? and id < ?))
                order by officer desc, id desc limit ?
                """, today, today, today, today, pattern, viewer,
                today, today, today, today, today, today, cursorGroup, cursorGroup, cursorGroup, cursorId, limit);
        if (people.isEmpty()) return people;
        String placeholders = String.join(",", java.util.Collections.nCopies(people.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(today);
        parameters.add(today);
        people.forEach(person -> parameters.add(person.get("id")));
        Map<Long, List<String>> organizations = new HashMap<>();
        jdbc.query("""
                select distinct o.user_id, a.name from alumni_organization_officers o
                join alumni_organizations a on a.id = o.organization_id
                where o.ended_at_recorded_at is null and o.effective_started_at <= ?
                  and (o.ended_at is null or o.ended_at >= ?) and a.dissolved_at is null
                  and o.user_id in (%s) order by a.name
                """.formatted(placeholders), rs -> {
                    long userId = rs.getLong("user_id");
                    organizations.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(rs.getString("name"));
                }, parameters.toArray());
        people.forEach(person -> person.put("organizations", organizations.getOrDefault(((Number) person.get("id")).longValue(), List.of())));
        return people;
    }
    public List<Map<String, Object>> unitAppointments(long userId, long viewer, LocalDate today) {
        return jdbc.queryForList("""
                select a.id as organizationId, a.name as organizationName, o.title,
                       o.effective_started_at as startedAt, o.ended_at as endedAt, o.president
                from alumni_organization_officers o join alumni_organizations a on a.id = o.organization_id
                join users u on u.id = o.user_id
                where o.user_id = ? and u.deleted_at is null and u.status <> 'WITHDRAWN' and u.account_blocked = false
                  and o.ended_at_recorded_at is null and o.effective_started_at <= ?
                  and (o.ended_at is null or o.ended_at >= ?) and a.dissolved_at is null
                  and not exists (select 1 from user_blocks b where b.blocker_id = ? and b.blocked_id = u.id and b.deleted_at is null)
                order by a.name, o.id desc
                """, userId, today, today, viewer);
    }

}
