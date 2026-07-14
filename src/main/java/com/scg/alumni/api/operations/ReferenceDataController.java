package com.scg.alumni.api.operations;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reference-data")
public class ReferenceDataController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public Map<String, Object> findReferenceData() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("majors", jdbcTemplate.query("""
                select id, name, normalized_name, status, display_major_id
                from majors
                order by name
                """, JdbcResponseMapper.INSTANCE));
        response.put("industries", jdbcTemplate.query("""
                select id, name
                from industries
                order by name
                """, JdbcResponseMapper.INSTANCE));
        response.put("companies", jdbcTemplate.query("""
                select c.id, c.name, c.work_zipcode, c.work_address1, c.work_address2,
                       c.description, c.industry_id, i.name as industry_name
                from companies c
                left join industries i on i.id = c.industry_id
                order by c.name
                """, JdbcResponseMapper.INSTANCE));
        response.put("hobbies", jdbcTemplate.query("""
                select h.id, h.name, count(uh.id) as member_count
                from hobbies h
                left join user_hobbies uh on uh.hobby_id = h.id
                group by h.id, h.name
                order by member_count desc, h.name
                """, JdbcResponseMapper.INSTANCE));
        response.put("officerTerms", jdbcTemplate.query("""
                select id, generation, phase, started_at, ended_at, current_term
                from officer_terms
                order by generation desc, phase desc
                """, JdbcResponseMapper.INSTANCE));
        response.put("officerRoles", jdbcTemplate.query("""
                select id, name, sort_order
                from officer_roles
                order by sort_order, id
                """, JdbcResponseMapper.INSTANCE));
        return response;
    }
}
