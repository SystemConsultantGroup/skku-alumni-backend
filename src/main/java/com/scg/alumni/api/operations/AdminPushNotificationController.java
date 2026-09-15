package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.CursorPageResponse;
import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.infrastructure.push.PushNotificationService;
import com.scg.alumni.infrastructure.push.PushLink;
import com.scg.alumni.infrastructure.push.PushNotificationService.DirectSendResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사무처가 고른 회원에게 알림을 직접 보낸다.
 *
 * <p>지금까지 푸시는 공지·뉴스를 올리면 임원 전체에게 따라 나가는 것뿐이었다.
 * 회비를 아직 내지 않은 몇 사람에게 안내하거나, 행사 당일 참석자에게만 장소를
 * 알리는 일은 사무처가 전화를 돌려 처리하고 있었다.
 *
 * <p>발송은 되돌릴 수 없다. 그래서 이 화면은 두 가지를 반드시 보여준다 — 고른
 * 사람 중 실제로 알림이 나가는 사람이 누구인지(알림을 꺼 두었거나 앱을 설치하지
 * 않은 회원은 골라도 나가지 않는다), 그리고 지난 발송 내역. 둘 다 없으면 "보냈다"
 * 는 말만 남고 무엇이 누구에게 갔는지는 아무도 모르게 된다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/push-notifications")
public class AdminPushNotificationController {

    /**
     * 한 번에 고를 수 있는 회원 수.
     *
     * <p>발송은 화면을 붙잡고 그 자리에서 끝낸다. 전 임원에게 보내는 일은 공지를
     * 올리는 자리가 따로 있으므로, 여기는 "몇 명에서 몇십 명"을 상정한다.
     */
    private static final int MAX_RECIPIENTS = 500;

    /** 발송 내역 목록에 미리 보여줄 수신자 이름 수. 나머지는 "외 N명"으로 줄인다. */
    private static final int RECIPIENT_PREVIEW_SIZE = 5;

    private final JdbcTemplate jdbcTemplate;
    private final PushNotificationService pushNotificationService;
    private final AdminAuditLog adminAuditLog;

    /**
     * 보낼 사람을 고르는 목록.
     *
     * <p>회원 관리 목록을 그대로 쓰지 않는다. 여기서 필요한 것은 "이 사람에게 알림이
     * 닿는가"인데, 그 정보(등록된 기기 수, 알림 설정)가 회원 목록에는 없다. 닿지 않는
     * 사람을 고르고 나서야 "0명에게 발송됨"을 보는 것은 화면의 잘못이다.
     */
    @GetMapping("/targets")
    public CursorPageResponse<Map<String, Object>> findTargets(
            @RequestParam(required = false) String keyword,
            /** 알림이 실제로 닿는 회원만 볼지. 기본은 전체를 보여주고 닿지 않는 이유를 함께 적는다. */
            @RequestParam(required = false) Boolean onlyReachable,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        String normalizedKeyword = normalizeLike(keyword);
        // 필터를 끈 상태를 null 로 넘긴다. false 를 그대로 비교하면 드라이버마다
        // 불리언 파라미터를 다르게 실어 보내서(H2 는 BOOLEAN, MySQL 은 TINYINT)
        // 조건이 조용히 어긋난다. 다른 목록 API 도 같은 방식으로 껐다 켠다.
        Boolean reachableOnly = Boolean.TRUE.equals(onlyReachable) ? Boolean.TRUE : null;
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select u.id, u.name, u.student_id, u.notification_enabled,
                       m.name as major_name,
                       orole.name as officer_role_name,
                       case when oh.id is not null then ot.generation end as generation,
                       case when oh.id is not null then ot.phase end as phase,
                       coalesce(dt.device_count, 0) as device_count
                from users u
                left join majors m on m.id = u.major_id
                left join officer_terms ot on ot.current_term = true
                left join officer_histories oh on oh.user_id = u.id and oh.officer_term_id = ot.id and oh.deleted_at is null
                left join officer_roles orole on orole.id = oh.officer_role_id
                left join (
                    select user_id, count(*) as device_count
                    from device_tokens
                    where deleted_at is null
                    group by user_id
                ) dt on dt.user_id = u.id
                where u.deleted_at is null
                  and u.status = 'ACTIVE'
                  and (? is null or u.id < ?)
                  and (? is null or lower(u.name) like ? or lower(coalesce(u.student_id, '')) like ?
                       or lower(coalesce(m.name, '')) like ?)
                  and (? is null or (u.notification_enabled = true and coalesce(dt.device_count, 0) > 0))
                order by u.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE,
                cursor, cursor,
                normalizedKeyword, normalizedKeyword, normalizedKeyword, normalizedKeyword,
                reachableOnly,
                CursorPageFactory.queryLimit(size));
        return CursorPageFactory.from(rows, size);
    }

    /**
     * 고른 회원에게 알림을 보낸다.
     *
     * <p>트랜잭션으로 묶지 않는다. FCM 호출은 네트워크 I/O 라 수백 건이면 몇 초가
     * 걸리는데, 그동안 DB 커넥션을 붙잡고 있을 이유가 없다. 기록이 발송보다 늦게
     * 남으므로, 기록이 없다고 해서 나가지 않았다는 뜻은 아니다 — 그래서 실패해도
     * 기록만은 남기려고 발송 뒤에 곧바로 적는다.
     */
    @PostMapping
    public Map<String, Object> send(@Valid @RequestBody PushMessageRequest request) {
        List<Long> memberIds = new ArrayList<>(new LinkedHashSet<>(request.memberIds()));
        if (memberIds.size() > MAX_RECIPIENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "한 번에 보낼 수 있는 인원은 " + MAX_RECIPIENTS + "명까지입니다. 나눠서 보내주세요.");
        }
        String title = request.title().trim();
        String body = request.body().trim();
        // 발송 전에 확인한다. 주소가 잘못됐다고 알림이 링크 없이 나가버리면 되돌릴 수 없다.
        PushLink link;
        try {
            link = PushLink.parse(request.linkUrl());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }

        DirectSendResult result = pushNotificationService.sendToMembers(memberIds, title, body, link);
        Long messageId = recordHistory(memberIds, title, body, link, result);
        adminAuditLog.record("SEND_PUSH_MESSAGE", "push_message", messageId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", messageId);
        response.put("requestedCount", memberIds.size());
        response.put("targetCount", result.devicesByMember().size());
        response.put("deviceCount", result.deviceCount());
        response.put("successCount", result.successCount());
        response.put("failureCount", result.failureCount());
        // 자격 증명이 없는 환경에서는 아무것도 나가지 않는다. 화면이 "발송 완료"라고
        // 말해버리면 사무처는 나가지도 않은 안내를 보냈다고 믿게 된다.
        response.put("dispatched", result.dispatched());
        response.put("linkUrl", link == null ? null : link.originalUrl());
        // 앱에서 열리는지 바깥 브라우저로 열리는지. 보낸 사람이 의도와 같은지 확인할 수 있게 한다.
        response.put("linkPath", link == null ? null : link.path());
        response.putAll(describeSkipped(memberIds, result));
        return response;
    }

    /** 지난 발송 내역. 같은 안내를 두 번 보내지 않으려면 무엇을 보냈는지 볼 수 있어야 한다. */
    @GetMapping
    public CursorPageResponse<Map<String, Object>> findMessages(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                select pm.id, pm.title, pm.body, pm.link_url, pm.requested_count, pm.target_count, pm.device_count,
                       pm.success_count, pm.failure_count, pm.created_at, a.name as admin_name
                from push_messages pm
                join admins a on a.id = pm.admin_id
                where (? is null or pm.id < ?)
                order by pm.id desc
                limit ?
                """, JdbcResponseMapper.INSTANCE, cursor, cursor, CursorPageFactory.queryLimit(size));
        CursorPageResponse<Map<String, Object>> page = CursorPageFactory.from(rows, size);
        attachRecipients(page.items());
        return page;
    }

    /**
     * 누구에게 보냈는지 붙인다.
     *
     * <p>목록 질의에서 이름을 이어 붙이지 않고 한 번 더 물어본다. GROUP_CONCAT 은
     * MySQL 과 H2 에서 정렬·길이 제한이 다르게 동작해서, 화면에 보이는 이름의 순서가
     * 운영과 테스트에서 달라진다.
     */
    private void attachRecipients(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<Long> messageIds = messages.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        String placeholders = String.join(", ", messageIds.stream().map(id -> "?").toList());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select pmr.push_message_id, u.name
                from push_message_recipients pmr
                join users u on u.id = pmr.user_id
                where pmr.push_message_id in (%s)
                order by pmr.id
                """.formatted(placeholders), messageIds.toArray());

        Map<Long, List<String>> namesByMessage = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long messageId = ((Number) row.get("push_message_id")).longValue();
            namesByMessage.computeIfAbsent(messageId, key -> new ArrayList<>()).add((String) row.get("name"));
        }
        for (Map<String, Object> message : messages) {
            List<String> names = namesByMessage.getOrDefault(((Number) message.get("id")).longValue(), List.of());
            message.put("recipientNames", names.stream().limit(RECIPIENT_PREVIEW_SIZE).toList());
            message.put("recipientCount", names.size());
        }
    }

    /**
     * 고른 사람 중 알림이 나가지 않은 사람을 이유별로 나눈다.
     *
     * <p>"5명 중 3명에게 보냈습니다"만 보여주면 빠진 두 사람이 누구인지 알 수 없어
     * 사무처는 결국 전화를 돌린다. 이름과 이유까지 있어야 다음 행동을 정할 수 있다.
     */
    private Map<String, Object> describeSkipped(List<Long> memberIds, DirectSendResult result) {
        List<Long> skippedIds = memberIds.stream().filter(id -> !result.devicesByMember().containsKey(id)).toList();
        List<String> notificationOff = new ArrayList<>();
        List<String> noDevice = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (!skippedIds.isEmpty()) {
            String placeholders = String.join(", ", skippedIds.stream().map(id -> "?").toList());
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select u.id, u.name, u.notification_enabled, u.status, u.deleted_at,
                           coalesce(dt.device_count, 0) as device_count
                    from users u
                    left join (
                        select user_id, count(*) as device_count
                        from device_tokens
                        where deleted_at is null
                        group by user_id
                    ) dt on dt.user_id = u.id
                    where u.id in (%s)
                    """.formatted(placeholders), skippedIds.toArray());
            Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
            rows.forEach(row -> byId.put(((Number) row.get("id")).longValue(), row));

            for (Long id : skippedIds) {
                Map<String, Object> row = byId.get(id);
                // 목록을 띄운 뒤 회원이 지워지거나 탈퇴하는 사이가 있다. 이름조차 없으면
                // 번호라도 보여줘야 사무처가 무엇이 빠졌는지 짚을 수 있다.
                if (row == null || row.get("deleted_at") != null || !"ACTIVE".equals(row.get("status"))) {
                    unavailable.add(row == null ? "회원 " + id : text(row.get("name")));
                    continue;
                }
                if (!isTrue(row.get("notification_enabled"))) {
                    notificationOff.add(text(row.get("name")));
                    continue;
                }
                noDevice.add(text(row.get("name")));
            }
        }
        return Map.of(
                "skippedNotificationOff", notificationOff,
                "skippedNoDevice", noDevice,
                "skippedUnavailable", unavailable);
    }

    private Long recordHistory(List<Long> memberIds, String title, String body, PushLink link,
            DirectSendResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("admin_id", AuthContext.currentAdminId());
        values.put("title", title);
        values.put("body", body);
        values.put("link_url", link == null ? null : link.originalUrl());
        values.put("requested_count", memberIds.size());
        values.put("target_count", result.devicesByMember().size());
        values.put("device_count", result.deviceCount());
        values.put("success_count", result.successCount());
        values.put("failure_count", result.failureCount());
        Number messageId = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("push_messages")
                .usingColumns(values.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(values);

        // 실제로 대상이 된 회원만 남긴다. 고르기만 하고 나가지 않은 사람을 수신자로
        // 적어두면, 나중에 "보냈는데 못 받았다"의 원인을 여기서 찾을 수 없게 된다.
        List<Object[]> recipients = result.devicesByMember().entrySet().stream()
                .map(entry -> new Object[]{messageId.longValue(), entry.getKey(), entry.getValue()})
                .toList();
        if (!recipients.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "insert into push_message_recipients (push_message_id, user_id, device_count) values (?, ?, ?)",
                    recipients);
        }
        return messageId.longValue();
    }

    /** MySQL 은 BOOLEAN 을 TINYINT 로 돌려주고 H2 는 Boolean 으로 돌려준다. */
    private boolean isTrue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return false;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String normalizeLike(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    }

    public record PushMessageRequest(
            @NotBlank @Size(max = 100, message = "제목은 100자까지 쓸 수 있습니다.") String title,
            @NotBlank @Size(max = 500, message = "내용은 500자까지 쓸 수 있습니다.") String body,
            @NotEmpty(message = "보낼 회원을 한 명 이상 골라주세요.") List<Long> memberIds,
            /** 알림을 누르면 열 웹 주소. 비워 두면 앱 홈이 열린다. */
            @Size(max = 1000, message = "웹 주소는 1000자까지 넣을 수 있습니다.") String linkUrl
    ) {
    }
}
