package com.scg.alumni.api.operations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/vips")
@RequiredArgsConstructor
public class AdminVipDirectoryController {
    private final JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.query("""
                select v.id, u.id as user_id, u.name, u.student_id, u.phone, u.status, u.deleted_at,
                       u.account_blocked, v.granted_at
                from vip_qualifications v join users u on u.id = v.user_id
                where v.revoked_at is null and (? is null or v.id < ?) order by v.id desc limit ?
                """, JdbcResponseMapper.INSTANCE, cursor, cursor, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<Map<String, Object>> items = hasNext ? rows.subList(0, limit) : rows;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("hasNext", hasNext);
        result.put("nextCursor", hasNext ? items.get(items.size() - 1).get("id") : null);
        return result;
    }
}
