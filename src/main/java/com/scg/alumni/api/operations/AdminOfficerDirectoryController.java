package com.scg.alumni.api.operations;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/officer-directory")
@RequiredArgsConstructor
public class AdminOfficerDirectoryController {
    private final JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (kind != null && !kind.equals("CENTRAL") && !kind.equals("UNIT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "중앙 임원 또는 단위동문회를 선택해 주세요.");
        }
        int limit = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, Math.min(page, 100000));
        String filter = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        List<Map<String, Object>> rows = jdbc.query("""
                select * from (
                  select 0 as priority, 'CENTRAL' as kind, h.id as record_id, u.id as user_id,
                         u.name, u.student_id, cast(null as char(255)) as organization_name,
                         r.name as title, h.started_at, h.ended_at, t.generation, t.phase,
                         case when exists (select 1 from officer_history_approvals a where a.officer_history_id = h.id)
                              then 'APPROVED' else 'PENDING' end as approval_status,
                         'CURRENT_RECORD' as record_type
                  from officer_histories h join users u on u.id = h.user_id
                  join officer_roles r on r.id = h.officer_role_id join officer_terms t on t.id = h.officer_term_id
                  where h.deleted_at is null
                  union all
                  select 0, 'CENTRAL', h.id, u.id, u.name, u.student_id, null, r.name,
                         h.started_at, h.ended_at, t.generation, t.phase,
                         case when exists (select 1 from officer_history_approvals a where a.officer_history_id = h.officer_history_id)
                              then 'APPROVED' else 'PENDING' end, 'PREVIOUS_ROLE'
                  from officer_role_change_history h join users u on u.id = h.user_id
                  join officer_histories oh on oh.id = h.officer_history_id and oh.deleted_at is null
                  join officer_roles r on r.id = h.officer_role_id join officer_terms t on t.id = h.officer_term_id
                  union all
                  select 1, 'UNIT', h.id, u.id, u.name, u.student_id, o.name, h.title,
                         h.effective_started_at, h.ended_at, null, null, null, 'UNIT_RECORD'
                  from alumni_organization_officers h join users u on u.id = h.user_id
                  join alumni_organizations o on o.id = h.organization_id
                ) directory
                where (? is null or kind = ?)
                  and (? is null or lower(coalesce(name, '')) like ? or lower(coalesce(student_id, '')) like ?
                       or lower(coalesce(organization_name, '')) like ? or lower(title) like ?)
                order by priority, started_at desc, record_type, record_id desc
                limit ? offset ?
                """, JdbcResponseMapper.INSTANCE, kind, kind, filter, filter, filter, filter, filter, limit + 1, (long) safePage * limit);
        boolean hasNext = rows.size() > limit;
        return Map.of("items", hasNext ? rows.subList(0, limit) : rows, "page", safePage, "hasNext", hasNext);
    }
}
