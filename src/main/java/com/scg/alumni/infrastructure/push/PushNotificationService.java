package com.scg.alumni.infrastructure.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
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
                where u.status = 'ACTIVE'
                  and u.notification_enabled = true
                  and u.notice_notification_enabled = true
                """, String.class);
        send(tokens, "NOTICE".equals(postKind) ? "새 공지" : "새 소식", title,
                Map.of("type", "official-post", "postKind", postKind, "postId", String.valueOf(postId)));
    }

    /** 동호회 글. 해당 동호회 회원 중 알림을 켜 둔 사람에게만 보낸다. */
    @Async
    public void notifyClubPost(Long clubId, String clubName, Long postId, String title, Long authorId) {
        List<String> tokens = jdbcTemplate.queryForList("""
                select dt.token
                from device_tokens dt
                join users u on u.id = dt.user_id
                join club_members cm on cm.user_id = u.id
                where cm.club_id = ?
                  and cm.left_at is null
                  and u.status = 'ACTIVE'
                  and u.notification_enabled = true
                  and u.club_notification_enabled = true
                  and u.id <> ?
                """, String.class, clubId, authorId);
        send(tokens, clubName, title,
                Map.of("type", "club-post", "clubId", String.valueOf(clubId), "postId", String.valueOf(postId)));
    }

    private void send(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens.isEmpty()) {
            return;
        }
        FirebaseApp app = firebaseApp.getIfAvailable();
        if (app == null) {
            // 조용히 넘어가면 운영에서 설정이 빠진 것을 눈치채지 못한다.
            log.warn("Firebase 가 설정되지 않아 알림 {}건을 보내지 못했습니다.", tokens.size());
            return;
        }
        log.info("푸시 발송을 시작합니다. 대상 {}건", tokens.size());
        FirebaseMessaging messaging = FirebaseMessaging.getInstance(app);
        List<String> staleTokens = new ArrayList<>();

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
                    if (!response.isSuccessful() && isStale(response.getException())) {
                        staleTokens.add(batch.get(index));
                    }
                }
            } catch (FirebaseMessagingException exception) {
                log.error("푸시 발송에 실패했습니다. 대상 {}건", batch.size(), exception);
            }
        }
        removeStaleTokens(staleTokens);
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
}
