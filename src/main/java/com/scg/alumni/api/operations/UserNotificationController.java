package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.global.security.AuthContext;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 앱 안 알림 센터.
 *
 * <p>읽음 상태는 서버가 쥔다. 기기에 두면 휴대전화를 바꾸거나 두 기기를 쓸 때 상태가
 * 갈라지고, 앱을 지웠다 깔면 전부 안 읽음으로 돌아간다.
 *
 * <p>모든 조회는 로그인한 회원 본인의 행으로만 좁힌다. 알림 번호는 푸시에 실려 기기
 * 밖으로 나가므로, 번호를 안다고 남의 알림을 읽을 수 있어서는 안 된다.
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final JdbcTemplate jdbcTemplate;

    private static final String SELECT = """
            select n.id, n.type, n.title, n.body, n.link_path, n.link_url, n.created_at, un.read_at
            from user_notifications un
            join notifications n on n.id = un.notification_id
            """;

    @GetMapping
    public CursorPageResponse<Map<String, Object>> findMine(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.query(SELECT + """
                where un.user_id = ?
                  and (? is null or n.id < ?)
                order by n.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                AuthContext.currentMemberId(), cursor, cursor, CursorPageFactory.queryLimit(size));
        CursorPageResponse<Map<String, Object>> page = CursorPageFactory.from(rows, size);
        page.items().forEach(UserNotificationController::addReadFlag);
        return page;
    }

    /** 헤더의 빨간 점이 쓴다. 개수가 아니라 있는지만 필요하지만 숫자도 함께 준다. */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from user_notifications where user_id = ? and read_at is null
                """, Long.class, AuthContext.currentMemberId());
        return Map.of("count", count == null ? 0 : count);
    }

    /** 푸시를 눌러 들어왔을 때 그 알림을 모달로 띄우려고 한 건을 읽는다. */
    @GetMapping("/{id}")
    public Map<String, Object> findOne(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query(SELECT + """
                where un.user_id = ? and n.id = ?
                """, JdbcResponseMapper.INSTANCE, AuthContext.currentMemberId(), id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.");
        }
        Map<String, Object> row = rows.get(0);
        addReadFlag(row);
        return row;
    }

    @PostMapping("/{id}/read")
    @Transactional
    public Map<String, Object> markRead(@PathVariable Long id) {
        // 이미 읽은 알림의 읽은 시각을 덮어쓰지 않는다. 남의 알림이면 0행이 바뀌고 아무 일도 없다.
        jdbcTemplate.update("""
                update user_notifications
                set read_at = CURRENT_TIMESTAMP
                where notification_id = ? and user_id = ? and read_at is null
                """, id, AuthContext.currentMemberId());
        return Map.of("id", id, "read", true);
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Object> markAllRead() {
        int updated = jdbcTemplate.update("""
                update user_notifications
                set read_at = CURRENT_TIMESTAMP
                where user_id = ? and read_at is null
                """, AuthContext.currentMemberId());
        return Map.of("updated", updated);
    }

    /** MySQL 과 H2 는 불리언 식을 다르게 돌려주므로(1/0, true/false) 자바에서 계산한다. */
    private static void addReadFlag(Map<String, Object> row) {
        row.put("read", row.get("readAt") != null);
    }
}
