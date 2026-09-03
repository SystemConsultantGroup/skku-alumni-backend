package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 직장 관리.
 *
 * <p>직장은 지금까지 회원이 프로필을 채우면서 만들어지기만 했다. 오타로 갈라진
 * 같은 회사를 합치거나 주소를 채워 넣을 곳이 없었다. 어느 회사에 어느 동문이
 * 있는지도 회원 목록을 하나씩 열어보는 수밖에 없었다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/companies")
public class AdminCompanyController {

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditLog adminAuditLog;

    /** 직장 표에서 누를 수 있는 칸. 화면 머리글과 짝이 맞아야 한다. */
    private static final Map<String, String> COMPANY_SORTS = Map.of(
            "name", "c.name",
            "industryName", AdminTableSort.nullsLast("i.name"),
            "address", AdminTableSort.nullsLast("c.work_address1"),
            "memberCount", "member_count",
            "createdAt", "c.created_at");

    @GetMapping
    public CursorPageResponse<Map<String, Object>> findCompanies(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long industryId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String like = normalizeLike(keyword);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select c.id, c.name, c.work_zipcode, c.work_address1, c.work_address2,
                       c.description, c.industry_id, i.name as industry_name, c.created_at,
                       (
                           select count(*) from users u
                           where u.company_id = c.id and u.deleted_at is null
                       ) as member_count
                from companies c
                left join industries i on i.id = c.industry_id
                where c.deleted_at is null
                  and (? is null or lower(c.name) like ? or lower(coalesce(c.work_address1, '')) like ?)
                  and (? is null or c.industry_id = ?)
                %s
                limit ? offset ?
                """.formatted(AdminTableSort.orderBy(COMPANY_SORTS, sort, order, "c.id")),
                JdbcResponseMapper.INSTANCE,
                like, like, like, industryId, industryId,
                CursorPageFactory.queryLimit(size), AdminTableSort.offset(cursor));
        return AdminTableSort.page(rows, cursor, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> findCompany(@PathVariable Long id) {
        return jdbcTemplate.query("""
                select c.id, c.name, c.work_zipcode, c.work_address1, c.work_address2,
                       c.description, c.industry_id, i.name as industry_name, c.created_at
                from companies c
                left join industries i on i.id = c.industry_id
                where c.id = ? and c.deleted_at is null
                """, JdbcResponseMapper.INSTANCE, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "직장을 찾을 수 없습니다."));
    }

    /** 이 직장에 다니는 동문. 이름을 누르면 회원 상세로 넘어간다. */
    @GetMapping("/{id}/members")
    public List<Map<String, Object>> findCompanyMembers(@PathVariable Long id) {
        return jdbcTemplate.query("""
                select u.id, u.name, u.student_id, u.job_title, u.status, u.profile_image_url,
                       m.name as major_name, u.admission_year, i.name as industry_name
                from users u
                left join majors m on m.id = u.major_id
                left join industries i on i.id = u.industry_id
                where u.company_id = ? and u.deleted_at is null
                order by u.name
                """, JdbcResponseMapper.INSTANCE, id);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> createCompany(@Valid @RequestBody CompanyRequest request) {
        String name = normalizeName(request.name());
        requireUniqueName(name, null);
        // 지운 직장과 이름이 겹치면 유일 제약에 걸린다. 새로 만들지 말고 되살린다.
        Long deletedId = findDeletedByName(name);
        if (deletedId != null) {
            update(deletedId, name, request);
            jdbcTemplate.update(
                    "update companies set deleted_at = null, updated_at = CURRENT_TIMESTAMP where id = ?",
                    deletedId);
            adminAuditLog.record("RESTORE_COMPANY", "company", deletedId);
            return findCompany(deletedId);
        }
        jdbcTemplate.update("""
                insert into companies (
                    name, work_zipcode, work_address1, work_address2, description, industry_id,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, name, blankToNull(request.workZipcode()), blankToNull(request.workAddress1()),
                blankToNull(request.workAddress2()), blankToNull(request.description()), request.industryId());
        Long id = jdbcTemplate.queryForObject("select id from companies where name = ?", Long.class, name);
        adminAuditLog.record("CREATE_COMPANY", "company", id);
        return findCompany(id);
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> updateCompany(@PathVariable Long id, @Valid @RequestBody CompanyRequest request) {
        String name = normalizeName(request.name());
        requireUniqueName(name, id);
        if (update(id, name, request) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "직장을 찾을 수 없습니다.");
        }
        adminAuditLog.record("UPDATE_COMPANY", "company", id);
        return findCompany(id);
    }

    /**
     * 직장을 지운다.
     *
     * <p>이 직장을 대표 직장으로 둔 회원이 있으면 막는다. 지우고 나면 그 회원의
     * 직장 칸이 조용히 비어버려서, 사무처는 무엇이 사라졌는지 알 수 없다.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> deleteCompany(@PathVariable Long id) {
        Integer memberCount = jdbcTemplate.queryForObject("""
                select count(*) from users where company_id = ? and deleted_at is null
                """, Integer.class, id);
        if (memberCount != null && memberCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이 직장에 소속된 회원이 " + memberCount + "명 있습니다. 회원의 직장을 먼저 옮겨주세요.");
        }
        int updated = jdbcTemplate.update("""
                update companies
                set deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null
                """, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "직장을 찾을 수 없습니다.");
        }
        adminAuditLog.record("DELETE_COMPANY", "company", id);
        return Map.of("id", id, "deleted", true);
    }

    private int update(Long id, String name, CompanyRequest request) {
        return jdbcTemplate.update("""
                update companies
                set name = ?, work_zipcode = ?, work_address1 = ?, work_address2 = ?,
                    description = ?, industry_id = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, name, blankToNull(request.workZipcode()), blankToNull(request.workAddress1()),
                blankToNull(request.workAddress2()), blankToNull(request.description()),
                request.industryId(), id);
    }

    private Long findDeletedByName(String name) {
        return jdbcTemplate.query("select id from companies where name = ? and deleted_at is not null",
                (resultSet, rowNum) -> resultSet.getLong(1), name)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void requireUniqueName(String name, Long selfId) {
        Integer duplicates = jdbcTemplate.queryForObject("""
                select count(*) from companies
                where deleted_at is null
                  and lower(replace(name, ' ', '')) = lower(replace(?, ' ', ''))
                  and (? is null or id <> ?)
                """, Integer.class, name, selfId, selfId);
        if (duplicates != null && duplicates > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 직장명입니다.");
        }
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "직장명이 필요합니다.");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeLike(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    public record CompanyRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 50) String workZipcode,
            @Size(max = 255) String workAddress1,
            @Size(max = 255) String workAddress2,
            String description,
            Long industryId
    ) {
    }
}
