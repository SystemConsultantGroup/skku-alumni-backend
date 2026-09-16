package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사무처가 고른 회원에게 보내는 알림.
 *
 * <p>테스트 환경에는 Firebase 자격 증명이 없어 실제 발송은 일어나지 않는다.
 * 여기서 확인하는 것은 "누구를 대상으로 삼았는가"다 — 알림을 꺼 둔 회원과 기기를
 * 등록하지 않은 회원을 골라도 그 사람에게는 나가지 않아야 하고, 그 사실이 보낸
 * 사람에게 이유와 함께 돌아와야 한다.
 */
@SpringBootTest
@SuppressWarnings("unchecked")
class AdminPushNotificationTest {

    @Autowired
    private AdminPushNotificationController controller;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "안상인"), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void onlyMembersWithADeviceAndNotificationsOnAreTargeted() {
        List<Long> memberIds = activeMemberIds(3);
        Long reachable = memberIds.get(0);
        Long notificationOff = memberIds.get(1);
        Long noDevice = memberIds.get(2);
        registerDevice(reachable, "token-reachable");
        registerDevice(notificationOff, "token-muted");
        jdbcTemplate.update("update users set notification_enabled = false where id = ?", notificationOff);
        // 알림은 켰지만 앱을 아직 깔지 않은 회원. 둘 다 해당하면 "알림 꺼짐"으로 묶이므로
        // 두 이유를 갈라 보려면 여기서 알림을 켜 두어야 한다.
        jdbcTemplate.update("update users set notification_enabled = true where id = ?", noDevice);

        Map<String, Object> result = controller.send(new AdminPushNotificationController.PushMessageRequest(
                "회비 납부 안내", "이번 주 금요일까지 부탁드립니다.", List.of(reachable, notificationOff, noDevice), null));

        assertThat(result.get("requestedCount")).isEqualTo(3);
        assertThat(result.get("targetCount")).isEqualTo(1);
        assertThat(result.get("deviceCount")).isEqualTo(1);
        assertThat((List<Object>) result.get("skippedNotificationOff")).containsExactly(nameOf(notificationOff));
        assertThat((List<Object>) result.get("skippedNoDevice")).containsExactly(nameOf(noDevice));
    }

    /** 발송은 되돌릴 수 없다. 무엇을 누구에게 보냈는지는 화면을 떠나도 남아야 한다. */
    @Test
    @Transactional
    void sentMessageIsKeptWithItsRecipients() {
        Long member = activeMemberIds(1).get(0);
        registerDevice(member, "token-history");

        Map<String, Object> result = controller.send(new AdminPushNotificationController.PushMessageRequest(
                "총회 장소 변경", "본관 2층으로 옮겼습니다.", List.of(member), null));
        Long messageId = (Long) result.get("id");

        Map<String, Object> saved = controller.findMessages(null, 10).items().stream()
                .filter(row -> ((Number) row.get("id")).longValue() == messageId)
                .findFirst()
                .orElseThrow();
        assertThat(saved.get("title")).isEqualTo("총회 장소 변경");
        assertThat(saved.get("recipientNames")).isEqualTo(List.of(nameOf(member)));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from push_message_recipients where push_message_id = ? and user_id = ?",
                Integer.class, messageId, member)).isOne();
    }

    /**
     * 자격 증명이 없으면 아무것도 나가지 않는다.
     *
     * <p>화면이 "발송 완료"라고만 말하면, 설정이 빠진 환경에서 사무처는 나가지도 않은
     * 안내를 보냈다고 믿는다.
     */
    @Test
    @Transactional
    void notDispatchedWhenFirebaseIsNotConfigured() {
        Long member = activeMemberIds(1).get(0);
        registerDevice(member, "token-undelivered");

        Map<String, Object> result = controller.send(new AdminPushNotificationController.PushMessageRequest(
                "안내", "본문", List.of(member), null));

        assertThat(result.get("dispatched")).isEqualTo(false);
        assertThat(result.get("successCount")).isEqualTo(0);
    }

    @Test
    @Transactional
    void reachableFilterHidesMembersWhoCannotBeReached() {
        List<Long> memberIds = activeMemberIds(2);
        Long member = memberIds.get(0);
        Long unreachable = memberIds.get(1);
        registerDevice(member, "token-filter");

        // 필터를 끈 목록에는 닿지 않는 회원도 나온다. 이유(알림 꺼짐·앱 미설치)를
        // 보고 문자로 안내할지 정하는 것도 사무처의 일이다.
        assertThat(targetIds(null)).contains(member, unreachable);

        List<Long> reachableIds = targetIds(true);

        assertThat(reachableIds).contains(member);
        assertThat(reachableIds).doesNotContain(unreachable);
        assertThat(reachableIds).allSatisfy(id -> assertThat(jdbcTemplate.queryForObject(
                "select count(*) from device_tokens where user_id = ? and deleted_at is null", Integer.class, id))
                .isPositive());
    }

    @Test
    @Transactional
    void tooManyRecipientsAreRejected() {
        List<Long> memberIds = java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList();

        assertThatThrownBy(() -> controller.send(new AdminPushNotificationController.PushMessageRequest(
                "안내", "본문", memberIds, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("나눠서 보내주세요");
    }

    /** 붙여 넣은 주소는 발송 내역에 그대로 남는다. 누른 사람이 어디로 갔는지 되짚을 수 있어야 한다. */
    @Test
    @Transactional
    void linkIsKeptWithTheMessage() {
        Long member = activeMemberIds(1).get(0);
        registerDevice(member, "token-link");

        Map<String, Object> result = controller.send(new AdminPushNotificationController.PushMessageRequest(
                "총회 안내", "자세한 내용은 공지를 확인해주세요.", List.of(member),
                "https://alumni.scg.skku.ac.kr/notices/12"));

        assertThat(result.get("linkPath")).isEqualTo("/notices/12");
        assertThat(jdbcTemplate.queryForObject("select link_url from push_messages where id = ?", String.class,
                result.get("id"))).isEqualTo("https://alumni.scg.skku.ac.kr/notices/12");
    }

    /** 주소가 틀렸다고 링크 없이 나가버리면 되돌릴 수 없다. 보내기 전에 막고, 기록도 남기지 않는다. */
    @Test
    @Transactional
    void adminSiteLinkIsRejectedBeforeSending() {
        Long member = activeMemberIds(1).get(0);
        registerDevice(member, "token-admin-link");
        Integer before = jdbcTemplate.queryForObject("select count(*) from push_messages", Integer.class);

        assertThatThrownBy(() -> controller.send(new AdminPushNotificationController.PushMessageRequest(
                "안내", "본문", List.of(member), "https://admin.alumni.scg.skku.ac.kr/content")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("관리자 사이트 주소");
        assertThat(jdbcTemplate.queryForObject("select count(*) from push_messages", Integer.class)).isEqualTo(before);
    }

    private List<Long> targetIds(Boolean onlyReachable) {
        return controller.findTargets(null, onlyReachable, null, 50).items().stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();
    }

    private List<Long> activeMemberIds(int count) {
        return jdbcTemplate.queryForList("""
                select id from users
                where deleted_at is null and status = 'ACTIVE'
                order by id
                limit ?
                """, Long.class, count);
    }

    private String nameOf(Long memberId) {
        return jdbcTemplate.queryForObject("select name from users where id = ?", String.class, memberId);
    }

    /**
     * 앱을 깔고 알림을 켠 회원을 만든다.
     *
     * <p>토큰 등록과 알림 설정은 앱에서 서로 다른 자리에서 일어난다(기기 등록은
     * 로그인 직후, 알림 켜기는 설정 화면). 기본값이 꺼짐이라 토큰만 넣으면
     * 발송 대상이 되지 않는다 — 실제로도 그렇다.
     */
    private void registerDevice(Long memberId, String token) {
        jdbcTemplate.update("""
                insert into device_tokens (user_id, token, platform, last_seen_at, created_at, updated_at)
                values (?, ?, 'ANDROID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, memberId, token);
        jdbcTemplate.update("update users set notification_enabled = true where id = ?", memberId);
    }
}
