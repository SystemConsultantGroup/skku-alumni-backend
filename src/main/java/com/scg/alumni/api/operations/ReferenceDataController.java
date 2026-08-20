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
                select i.id, i.name,
                       (
                           select count(*)
                           from users u
                           where u.industry_id = i.id
                       ) as member_count,
                       (
                           select count(*)
                           from posts p
                           where p.industry_id = i.id
                             and p.post_kind = 'BUSINESS'
                             and p.status = 'PUBLISHED'
                       ) as business_post_count
                from industries i
                order by i.name
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
        response.put("regions", jdbcTemplate.queryForList("""
                select region from (
                    select distinct substring_index(trim(work_address1), ' ', 1) as region
                    from companies where work_address1 is not null and trim(work_address1) <> ''
                    union
                    select distinct substring_index(trim(work_address1), ' ', 1) as region
                    from users where work_address1 is not null and trim(work_address1) <> ''
                    union
                    select distinct substring_index(trim(home_address1), ' ', 1) as region
                    from users
                    where home_address_public = true
                      and home_address1 is not null and trim(home_address1) <> ''
                ) regions
                where region <> ''
                order by region
                """, String.class));
        response.put("officerTerms", jdbcTemplate.query("""
                select id, generation, phase, started_at, ended_at, current_term
                from officer_terms
                order by generation desc, phase desc
                """, JdbcResponseMapper.INSTANCE));
        // 기여금까지 함께 내려준다. 소개 화면의 기여금 표가 사무처가 설정한 값을
        // 그대로 보여줘야 한다. 화면에 금액을 따로 적어두면 조정될 때마다 어긋난다.
        response.put("officerRoles", jdbcTemplate.query("""
                select id, name, sort_order, dues_amount, dues_note
                from officer_roles
                order by sort_order, id
                """, JdbcResponseMapper.INSTANCE));
        return response;
    }
}
