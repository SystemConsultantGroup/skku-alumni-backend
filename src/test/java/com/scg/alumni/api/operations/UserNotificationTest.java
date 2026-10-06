package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 앱 안 알림 센터. 읽음 상태는 서버가 쥐고, 남의 알림은 번호를 알아도 열 수 없다.
 */
@SpringBootTest
class UserNotificationTest {

    @Autowired
    private AdminPushNotificationController adminController;

    @Autowired
    private UserNotificationController controller;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 링크가 없으면 눌러도 홈만 열렸다. 센터의 그 알림으로 데려가 내용을 보여줘야 한다. */
    @Test
    @Transactional
    void directMessageLandsInTheCenterUnreadAndCanBeMarkedRead() {
        List<Long> members = activeMemberIds(2);
        Long receiver = members.get(0);
        Long other = members.get(1);
        jdbcTemplate.update("update users set notification_enabled = true where id in (?, ?)", receiver, other);

        signInAsAdmin();
        adminController.send(new AdminPushNotificationController.PushMessageRequest(
                "총회 안내", "본관 2층입니다.", List.of(receiver), null));

        signInAsMember(receiver);
        assertThat(controller.unreadCount().get("count")).isEqualTo(1L);
        Map<String, Object> item = controller.findMine(null, 10).items().get(0);
        assertThat(item.get("title")).isEqualTo("총회 안내");
        assertThat(item.get("read")).isEqualTo(false);
        assertThat(item.get("linkPath")).isNull();

        Long id = ((Number) item.get("id")).longValue();
        controller.markRead(id);
        assertThat(controller.unreadCount().get("count")).isEqualTo(0L);
        assertThat(controller.findOne(id).get("read")).isEqualTo(true);

        // 받지 않은 사람에게는 보이지 않는다. 알림 번호는 푸시에 실려 기기 밖으로 나간다.
        signInAsMember(other);
        assertThat(controller.unreadCount().get("count")).isEqualTo(0L);
        assertThatThrownBy(() -> controller.findOne(id)).isInstanceOf(ResponseStatusException.class);
        controller.markRead(id);
        signInAsMember(receiver);
        assertThat(controller.findOne(id).get("readAt")).isNotNull();
    }

    @Test
    @Transactional
    void notificationsOffMembersDoNotReceiveAndReadAllClearsTheDot() {
        List<Long> members = activeMemberIds(2);
        Long muted = members.get(0);
        Long receiver = members.get(1);
        jdbcTemplate.update("update users set notification_enabled = false where id = ?", muted);
        jdbcTemplate.update("update users set notification_enabled = true where id = ?", receiver);

        signInAsAdmin();
        adminController.send(new AdminPushNotificationController.PushMessageRequest(
                "하나", "본문", List.of(muted, receiver), null));
        adminController.send(new AdminPushNotificationController.PushMessageRequest(
                "둘", "본문", List.of(receiver), "https://alumni.scg.skku.ac.kr/notices/3"));

        signInAsMember(muted);
        assertThat(controller.unreadCount().get("count")).isEqualTo(0L);

        signInAsMember(receiver);
        assertThat(controller.unreadCount().get("count")).isEqualTo(2L);
        // 최신순이고, 링크가 있는 알림은 앱 안 경로를 가진다.
        List<Map<String, Object>> items = controller.findMine(null, 10).items();
        assertThat(items.get(0).get("title")).isEqualTo("둘");
        assertThat(items.get(0).get("linkPath")).isEqualTo("/notices/3");

        controller.markAllRead();
        assertThat(controller.unreadCount().get("count")).isEqualTo(0L);
    }

    private void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "안상인"), null));
    }

    private void signInAsMember(Long id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(id, AuthScope.MEMBER, "회원"), null));
    }

    private List<Long> activeMemberIds(int count) {
        return jdbcTemplate.queryForList("""
                select id from users
                where deleted_at is null and status = 'ACTIVE'
                order by id
                limit ?
                """, Long.class, count);
    }
}
