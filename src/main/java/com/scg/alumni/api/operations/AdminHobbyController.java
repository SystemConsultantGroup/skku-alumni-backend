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
 * 취미 관리.
 *
 * <p>취미는 회원이 프로필에서 직접 만들 수 있어 '골프'와 '골프 ' 처럼 갈라지기
 * 쉽다. 갈라진 취미는 동문 검색의 취미 필터를 쓸모없게 만든다. 사무처가 이름을
 * 고치고 쓰지 않는 것을 지울 자리가 필요하다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/hobbies")
public class AdminHobbyController {

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditLog adminAuditLog;

    /** 취미 표에서 누를 수 있는 칸. 화면 머리글과 짝이 맞아야 한다. */
    private static final Map<String, String> HOBBY_SORTS = Map.of(
            "name", "h.name",
            "memberCount", "member_count",
            "createdAt", "h.created_at");

    @GetMapping
    public CursorPageResponse<Map<String, Object>> findHobbies(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String like = normalizeLike(keyword);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select h.id, h.name, h.created_at,
                       (
                           select count(*)
                           from user_hobbies uh
                           join users u on u.id = uh.user_id
                           where uh.hobby_id = h.id and uh.deleted_at is null and u.deleted_at is null
                       ) as member_count
                from hobbies h
                where h.deleted_at is null
                  and (? is null or lower(h.name) like ?)
                %s
                limit ? offset ?
                """.formatted(AdminTableSort.orderBy(HOBBY_SORTS, sort, order, "h.id")),
                JdbcResponseMapper.INSTANCE,
                like, like, CursorPageFactory.queryLimit(size), AdminTableSort.offset(cursor));
        return AdminTableSort.page(rows, cursor, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> findHobby(@PathVariable Long id) {
        return jdbcTemplate.query("""
                select h.id, h.name, h.created_at
                from hobbies h
                where h.id = ? and h.deleted_at is null
                """, JdbcResponseMapper.INSTANCE, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "취미를 찾을 수 없습니다."));
    }

    /** 이 취미를 가진 동문. 이름을 누르면 회원 상세로 넘어간다. */
    @GetMapping("/{id}/members")
    public List<Map<String, Object>> findHobbyMembers(@PathVariable Long id) {
        return jdbcTemplate.query("""
                select u.id, u.name, u.student_id, u.job_title, u.status, u.profile_image_url,
                       m.name as major_name, u.admission_year, co.name as company_name
                from user_hobbies uh
                join users u on u.id = uh.user_id
                left join majors m on m.id = u.major_id
                left join companies co on co.id = u.company_id and co.deleted_at is null
                where uh.hobby_id = ? and uh.deleted_at is null and u.deleted_at is null
                order by u.name
                """, JdbcResponseMapper.INSTANCE, id);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> createHobby(@Valid @RequestBody HobbyRequest request) {
        String name = normalizeName(request.name());
        requireUniqueName(name, null);
        // 지운 취미와 이름이 겹치면 유일 제약에 걸린다. 새로 만들지 말고 되살린다.
        Long deletedId = jdbcTemplate.query(
                "select id from hobbies where name = ? and deleted_at is not null",
                (resultSet, rowNum) -> resultSet.getLong(1), name)
                .stream()
                .findFirst()
                .orElse(null);
        if (deletedId != null) {
            jdbcTemplate.update(
                    "update hobbies set deleted_at = null, updated_at = CURRENT_TIMESTAMP where id = ?", deletedId);
            adminAuditLog.record("RESTORE_HOBBY", "hobby", deletedId);
            return findHobby(deletedId);
        }
        jdbcTemplate.update("""
                insert into hobbies (name, created_at, updated_at)
                values (?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, name);
        Long id = jdbcTemplate.queryForObject("select id from hobbies where name = ?", Long.class, name);
        adminAuditLog.record("CREATE_HOBBY", "hobby", id);
        return findHobby(id);
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> updateHobby(@PathVariable Long id, @Valid @RequestBody HobbyRequest request) {
        String name = normalizeName(request.name());
        requireUniqueName(name, id);
        int updated = jdbcTemplate.update("""
                update hobbies set name = ?, updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null
                """, name, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "취미를 찾을 수 없습니다.");
        }
        adminAuditLog.record("UPDATE_HOBBY", "hobby", id);
        return findHobby(id);
    }

    /**
     * 취미를 지운다. 연결(user_hobbies)은 그대로 둔다.
     *
     * <p>연결까지 지우면 취미를 되살려도 누가 그 취미를 갖고 있었는지 돌아오지
     * 않는다. 대신 취미를 읽는 모든 곳이 지운 취미를 걸러내므로, 회원 상세나
     * 동문 검색에는 나오지 않는다.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> deleteHobby(@PathVariable Long id) {
        int updated = jdbcTemplate.update("""
                update hobbies
                set deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ? and deleted_at is null
                """, id);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "취미를 찾을 수 없습니다.");
        }
        adminAuditLog.record("DELETE_HOBBY", "hobby", id);
        return Map.of("id", id, "deleted", true);
    }

    private void requireUniqueName(String name, Long selfId) {
        Integer duplicates = jdbcTemplate.queryForObject("""
                select count(*) from hobbies
                where deleted_at is null
                  and lower(replace(name, ' ', '')) = lower(replace(?, ' ', ''))
                  and (? is null or id <> ?)
                """, Integer.class, name, selfId, selfId);
        if (duplicates != null && duplicates > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 취미입니다.");
        }
    }

    private String normalizeName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "취미 이름이 필요합니다.");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String normalizeLike(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    public record HobbyRequest(@NotBlank @Size(max = 255) String name) {
    }
}
