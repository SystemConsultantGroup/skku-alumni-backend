package com.scg.alumni.infrastructure.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 공지·동호회 소식을 회원 기기로 밀어준다.
 *
 * <p>앱은 이미 FCM 토큰을 발급받고 있었지만 서버에서 보내는 쪽이 없어서, 회의에서
 * 시연한 "공지를 올리면 알림이 뜬다"가 실제로는 수동 발송 테스트뿐이었다.
 *
 * <p>발송은 게시글 저장과 분리한다. 회원이 수천 명이 되면 발송에 몇 분이 걸리는데,
 * 그동안 글쓰기 응답을 붙잡고 있을 이유가 없다. 발송이 실패해도 게시글은 남아야 한다.
 *
 * <p>사무처가 고른 회원에게 직접 보내는 알림({@link #sendToMembers})만은 그 자리에서
 * 보내고 결과를 돌려준다. 보낸 사람이 화면 앞에 서서 "몇 명에게 나갔는지"를 기다리고
 * 있고, 되돌릴 수 없는 일이라 나중에 로그를 뒤져 확인하게 두어서는 안 된다.
 */
@Slf4j
@Service
public class PushNotificationService {

    /** FCM 멀티캐스트 한 번에 보낼 수 있는 토큰 수. */
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<FirebaseApp> firebaseApp;

    public PushNotificationService(JdbcTemplate jdbcTemplate, ObjectProvider<FirebaseApp> firebaseApp) {
        this.jdbcTemplate = jdbcTemplate;
        this.firebaseApp = firebaseApp;
    }

    /** 공지·뉴스. 알림을 켜 둔 현행 임원 전체에게 보낸다. */
    @Async
    public void notifyOfficialPost(Long postId, String postKind, String title) {
        List<String> tokens = jdbcTemplate.queryForList("""
                select dt.token
                from device_tokens dt
                join users u on u.id = dt.user_id
                where dt.deleted_at is null
                  and u.deleted_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                  and u.notice_notification_enabled = true
                """, String.class);
        String pushTitle = "NOTICE".equals(postKind) ? "새 공지" : "새 소식";
        // 알림 센터에는 기기가 없는 회원도 받는다. 푸시는 기기로, 센터는 계정으로 닿는다.
        List<Long> recipients = jdbcTemplate.queryForList("""
                select u.id
                from users u
                where u.deleted_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                  and u.notice_notification_enabled = true
                """, Long.class);
        Long notificationId = createNotification("official-post", pushTitle, title, "/notices/" + postId, null, recipients);
        send(tokens, pushTitle, title,
                Map.of("type", "official-post", "postKind", postKind, "postId", String.valueOf(postId),
                        "notificationId", String.valueOf(notificationId)));
    }

    /**
     * 동호회 글. 해당 동호회 회원 중 알림을 켜 둔 사람에게만 보낸다.
     *
     * <p>clubCategory 를 함께 실어 보낸다. 앱이 알림을 눌렀을 때 열어야 할 주소가
     * /community/{club|research}/{clubId}/posts/{postId} 라서, 카테고리를 모르면
     * 경로를 만들지 못한다.
     */
    @Async
    public void notifyClubPost(Long clubId, String clubName, String clubCategory, Long postId, String title, Long authorId) {
        List<String> tokens = jdbcTemplate.queryForList("""
                select dt.token
                from device_tokens dt
                join users u on u.id = dt.user_id
                join club_members cm on cm.user_id = u.id
                where dt.deleted_at is null
                  and u.deleted_at is null
                  and cm.club_id = ?
                  and cm.left_at is null and cm.deleted_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                  and u.club_notification_enabled = true
                  and u.id <> ?
                """, String.class, clubId, authorId);
        List<Long> recipients = jdbcTemplate.queryForList("""
                select u.id
                from users u
                join club_members cm on cm.user_id = u.id
                where u.deleted_at is null
                  and cm.club_id = ?
                  and cm.left_at is null and cm.deleted_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                  and u.club_notification_enabled = true
                  and u.id <> ?
                """, Long.class, clubId, authorId);
        String section = "RESEARCH".equals(clubCategory) ? "research" : "club";
        Long notificationId = createNotification("club-post", clubName, title,
                "/community/" + section + "/" + clubId + "/posts/" + postId, null, recipients);
        send(tokens, clubName, title, Map.of(
                "type", "club-post",
                "clubId", String.valueOf(clubId),
                "clubCategory", clubCategory == null ? "" : clubCategory,
                "postId", String.valueOf(postId),
                "notificationId", String.valueOf(notificationId)));
    }

    /**
     * 사무처가 고른 회원에게 직접 보낸다.
     *
     * <p>공지·동호회 알림과 달리 종류별 스위치(notice/club)는 보지 않는다. 그 스위치는
     * "글이 올라올 때마다 오는 알림"을 줄이려는 것이지, 사무처가 나를 지목해 보내는
     * 안내까지 받지 않겠다는 뜻이 아니다. 다만 전체 스위치를 끈 회원에게는 보내지
     * 않는다 — 그 사람은 앱 알림 자체를 받지 않기로 한 것이다.
     *
     * <p>어느 회원의 기기 몇 대가 대상이 되었는지 함께 돌려준다. 고른 사람과 실제로
     * 나간 사람이 다를 수 있고(알림 꺼짐·기기 미등록), 그 차이는 보낸 사람이 화면에서
     * 바로 봐야 하는 정보다.
     *
     * <p>link 가 있으면 앱이 알림을 눌렀을 때 그 화면을 연다(PushLink 참고). 없으면 홈이 열린다.
     */
    public DirectSendResult sendToMembers(List<Long> memberIds, String title, String body, PushLink link) {
        if (memberIds.isEmpty()) {
            return new DirectSendResult(Map.of(), 0, 0, 0, false);
        }
        String placeholders = String.join(", ", memberIds.stream().map(id -> "?").toList());
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select u.id as user_id, dt.token
                from users u
                join device_tokens dt on dt.user_id = u.id and dt.deleted_at is null
                where u.id in (%s)
                  and u.deleted_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                """.formatted(placeholders), memberIds.toArray());

        Map<Long, Integer> devicesByMember = new LinkedHashMap<>();
        List<String> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long memberId = ((Number) row.get("user_id")).longValue();
            devicesByMember.merge(memberId, 1, Integer::sum);
            tokens.add((String) row.get("token"));
        }

        // 알림 센터에는 기기를 등록하지 않은 회원도 받는다. 알림을 끈 회원만 뺀다.
        List<Long> recipients = jdbcTemplate.queryForList("""
                select u.id
                from users u
                where u.id in (%s)
                  and u.deleted_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                """.formatted(placeholders), Long.class, memberIds.toArray());
        Long notificationId = createNotification("admin-message", title, body,
                link == null ? null : link.path(), link == null ? null : link.externalUrl(), recipients);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "admin-message");
        data.put("notificationId", String.valueOf(notificationId));
        if (link != null) {
            data.putAll(link.payload());
        } else {
            // 걸린 링크가 없으면 눌러도 홈만 열려 내용을 볼 수 없었다. 알림 센터의 이 알림으로
            // 데려가 내용을 모달로 띄운다. 앱은 admin-message 의 path 를 그대로 열므로
            // 이미 설치된 앱도 고치지 않고 동작한다.
            data.put("path", "/notifications?open=" + notificationId);
        }
        SendOutcome outcome = send(tokens, title, body, data);
        return new DirectSendResult(devicesByMember, tokens.size(), outcome.successCount(), outcome.failureCount(),
                outcome.dispatched());
    }

    /**
     * 알림 한 건과 받는 회원별 안 읽음 행을 남긴다. 발송보다 먼저 호출한다 — 푸시에 알림
     * 번호를 실어 보내야 눌렀을 때 그 알림을 찾아 열 수 있다.
     *
     * <p>푸시 발송 성공 여부와 무관하게 남긴다. 센터는 기기가 아니라 계정에 닿는 통로다.
     */
    private Long createNotification(String type, String title, String body, String linkPath, String linkUrl,
                                    List<Long> recipientIds) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into notifications (type, title, body, link_path, link_url, created_at)
                    values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setString(1, type);
            statement.setString(2, title);
            statement.setString(3, body);
            statement.setString(4, linkPath);
            statement.setString(5, linkUrl);
            return statement;
        }, keyHolder);
        // 키 컬럼을 id 로 못박아야 DB 마다 같은 모양으로 온다(H2 는 전 컬럼을, MySQL 은 GENERATED_KEY 를 준다).
        Long notificationId = keyHolder.getKey().longValue();
        jdbcTemplate.batchUpdate("""
                insert into user_notifications (notification_id, user_id, created_at)
                values (?, ?, CURRENT_TIMESTAMP)
                """, recipientIds.stream().distinct().map(userId -> new Object[]{notificationId, userId}).toList());
        return notificationId;
    }

    /**
     * 사무처가 문의에 답했음을 문의한 회원에게 알린다.
     *
     * <p>알림 센터에는 항상 남기고, 휴대전화 푸시는 알림을 켜 둔 회원에게만 보낸다. 눌렀을 때는
     * 그 문의의 답변 화면(/inquiries/{id})을 연다. 앱은 admin-message 의 path 를 앱 안에서
     * 그대로 열기 때문에 네이티브 앱을 고치지 않아도 된다.
     */
    @Async
    public void notifyInquiryAnswer(Long userId, Long inquiryId, String inquiryTitle) {
        deliverInquiryAnswer(userId, inquiryId, inquiryTitle);
    }

    /** {@link #notifyInquiryAnswer} 의 본체. 비동기 래퍼 없이 같은 스레드에서 돈다. */
    void deliverInquiryAnswer(Long userId, Long inquiryId, String inquiryTitle) {
        List<Long> recipients = jdbcTemplate.queryForList("""
                select u.id from users u
                where u.id = ? and u.deleted_at is null and u.status = 'ACTIVE'
                """, Long.class, userId);
        if (recipients.isEmpty()) {
            return;
        }
        String path = "/inquiries/" + inquiryId;
        String title = "문의하신 내용에 답변이 달렸습니다";
        Long notificationId = createNotification("inquiry-answer", title, inquiryTitle, path, null, recipients);
        List<String> tokens = jdbcTemplate.queryForList("""
                select dt.token
                from device_tokens dt
                join users u on u.id = dt.user_id
                where dt.deleted_at is null and u.id = ? and u.notification_enabled = true
                """, String.class, userId);
        send(tokens, title, inquiryTitle, Map.of(
                "type", "admin-message",
                "path", path,
                "notificationId", String.valueOf(notificationId)));
    }

    private SendOutcome send(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens.isEmpty()) {
            return new SendOutcome(0, 0, false);
        }
        FirebaseApp app = firebaseApp.getIfAvailable();
        if (app == null) {
            // 조용히 넘어가면 운영에서 설정이 빠진 것을 눈치채지 못한다.
            log.warn("Firebase 가 설정되지 않아 알림 {}건을 보내지 못했습니다.", tokens.size());
            return new SendOutcome(0, 0, false);
        }
        log.info("푸시 발송을 시작합니다. 대상 {}건", tokens.size());
        FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);
        List<String> staleTokens = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int start = 0; start < tokens.size(); start += BATCH_SIZE) {
            List<String> batch = tokens.subList(start, Math.min(start + BATCH_SIZE, tokens.size()));
            MulticastMessage message = MulticastMessage.builder()
                    .addAllTokens(batch)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putAllData(data)
                    .build();
            try {
                List<SendResponse> responses = messaging.sendEachForMulticast(message).getResponses();
                for (int index = 0; index < responses.size(); index++) {
                    SendResponse response = responses.get(index);
                    if (response.isSuccessful()) {
                        successCount++;
                        continue;
                    }
                    failureCount++;
                    if (isStale(response.getException())) {
                        staleTokens.add(batch.get(index));
                    }
                }
            } catch (FirebaseMessagingException exception) {
                failureCount += batch.size();
                log.error("푸시 발송에 실패했습니다. 대상 {}건", batch.size(), exception);
            }
        }
        removeStaleTokens(staleTokens);
        return new SendOutcome(successCount, failureCount, true);
    }

    /** 앱을 지웠거나 토큰이 갈린 기기. 남겨두면 발송할 때마다 실패한다. */
    private boolean isStale(FirebaseMessagingException exception) {
        if (exception == null) {
            return false;
        }
        MessagingErrorCode code = exception.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
    }

    private void removeStaleTokens(List<String> staleTokens) {
        if (staleTokens.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("delete from device_tokens where token = ?",
                staleTokens.stream().map(token -> new Object[]{token}).toList());
        log.info("사용할 수 없는 기기 토큰 {}건을 정리했습니다.", staleTokens.size());
    }

    /**
     * 직접 발송의 결과.
     *
     * @param devicesByMember 실제 발송 대상이 된 회원과 그 회원의 기기 수
     * @param dispatched      FCM 에 실제로 넘겼는지. 자격 증명이 없거나 대상이 없으면 false
     */
    public record DirectSendResult(
            Map<Long, Integer> devicesByMember,
            int deviceCount,
            int successCount,
            int failureCount,
            boolean dispatched
    ) {
    }

    private record SendOutcome(int successCount, int failureCount, boolean dispatched) {
    }
}
