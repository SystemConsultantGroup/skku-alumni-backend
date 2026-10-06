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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.scg.alumni.infrastructure.push.PushNotificationService;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import org.springframework.web.server.ResponseStatusException;

/** 문의하기. 한 번 묻고 한 번 답받는 구조이며, 남의 문의는 번호를 알아도 열 수 없다. */
@SpringBootTest
class InquiryTest {

    @Autowired
    private UserInquiryController userController;

    @Autowired
    private AdminInquiryController adminController;

    /**
     * 알림 발송은 비동기라 테스트 트랜잭션 밖 스레드에서 DB 에 쓴다. 진짜 서비스를 두면 다른
     * 테스트에 알림 행이 새어 나가므로, 여기서는 "처음 답할 때만 불렀는가"만 확인한다.
     * 알림이 센터에 남는 것은 InquiryAnswerNotificationTest 가 본다.
     */
    @MockitoBean
    private PushNotificationService pushNotificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void askThenAnswerThenTheMemberIsToldInTheCenter() {
        List<Long> members = activeMemberIds(2);
        Long asker = members.get(0);
        Long stranger = members.get(1);

        signInAsMember(asker);
        Long id = (Long) userController.create(new UserInquiryController.InquiryRequest(
                "DUES", "회비 영수증", "영수증을 받을 수 있나요?")).get("id");
        // 접수 전에는 목록에도 사무처 화면에도 없다.
        assertThat(userController.findMine(null, 10).items()).isEmpty();
        signInAsAdmin();
        assertThat(adminController.findInquiries(null, null, null, 1, 10).items()).isEmpty();

        signInAsMember(asker);
        userController.submit(id);
        userController.submit(id); // 두 번 눌러도 한 건이다
        assertThat(userController.findMine(null, 10).items()).hasSize(1);
        assertThat(userController.findOne(id).get("status")).isEqualTo("OPEN");

        // 남의 문의는 열 수 없다.
        signInAsMember(stranger);
        assertThatThrownBy(() -> userController.findOne(id)).isInstanceOf(ResponseStatusException.class);
        assertThat(userController.findMine(null, 10).items()).isEmpty();

        // 사무처가 답한다.
        signInAsAdmin();
        Map<String, Object> listed = adminController.findInquiries("영수증", "OPEN", "DUES", 1, 10).items().get(0);
        assertThat(listed.get("title")).isEqualTo("회비 영수증");
        assertThat(adminController.answer(id, new AdminInquiryController.AnswerRequest("메일로 보내드렸습니다.")).get("notified"))
                .isEqualTo(true);
        // 답을 고칠 때는 다시 알리지 않는다.
        assertThat(adminController.answer(id, new AdminInquiryController.AnswerRequest("메일로 보내드렸습니다 (수정)")).get("notified"))
                .isEqualTo(false);

        signInAsMember(asker);
        Map<String, Object> mine = userController.findOne(id);
        assertThat(mine.get("status")).isEqualTo("ANSWERED");
        assertThat(mine.get("answerBody")).isEqualTo("메일로 보내드렸습니다 (수정)");
        verify(pushNotificationService, times(1)).notifyInquiryAnswer(anyLong(), anyLong(), anyString());
    }

    @Test
    @Transactional
    void invalidCategoryAndTooManyOrBlockedAttachmentsAreRejected() {
        Long asker = activeMemberIds(1).get(0);
        signInAsMember(asker);
        assertThatThrownBy(() -> userController.create(new UserInquiryController.InquiryRequest("NOPE", "제목", "본문")))
                .isInstanceOf(ResponseStatusException.class);

        Long id = (Long) userController.create(new UserInquiryController.InquiryRequest("OTHER", "제목", "본문")).get("id");
        // 실행 파일은 저장소에 닿기 전에 막는다.
        assertThatThrownBy(() -> userController.addAttachment(id,
                new MockMultipartFile("file", "run.exe", "application/octet-stream", new byte[]{1})))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("올릴 수 없는 파일");
        // 5개를 채운 상태에서는 더 받지 않는다.
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("""
                    insert into inquiry_attachments (inquiry_id, original_name, object_name, content_type, size_bytes)
                    values (?, ?, ?, 'text/plain', 1)
                    """, id, "f" + i + ".txt", "inquiries/x" + i);
        }
        assertThatThrownBy(() -> userController.addAttachment(id,
                new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1})))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("5개");
        // 접수한 문의에는 첨부를 더하지 못한다.
        userController.submit(id);
        assertThatThrownBy(() -> userController.addAttachment(id,
                new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1})))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @Transactional
    void adminCanEditDeleteAndManageNotifyEmails() {
        Long asker = activeMemberIds(1).get(0);
        signInAsAdmin();
        Long id = (Long) adminController.createInquiry(new AdminInquiryController.InquiryCreateRequest(
                asker, "OTHER", "전화 문의", "전화로 받았습니다.")).get("id");
        adminController.updateInquiry(id, new AdminInquiryController.InquiryBody("DUES", "전화 문의(수정)", "내용"));
        assertThat(adminController.findInquiry(id).get("title")).isEqualTo("전화 문의(수정)");
        adminController.deleteInquiry(id);
        assertThatThrownBy(() -> adminController.findInquiry(id)).isInstanceOf(ResponseStatusException.class);

        adminController.addNotifyEmail(new AdminInquiryController.NotifyEmailRequest("Staff@Example.com"));
        assertThatThrownBy(() -> adminController.addNotifyEmail(new AdminInquiryController.NotifyEmailRequest("staff@example.com")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 등록");
        Map<String, Object> saved = adminController.findNotifyEmails().get(0);
        assertThat(saved.get("email")).isEqualTo("staff@example.com");
        adminController.removeNotifyEmail(((Number) saved.get("id")).longValue());
        assertThat(adminController.findNotifyEmails()).isEmpty();
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
                select id from users where deleted_at is null and status = 'ACTIVE' order by id limit ?
                """, Long.class, count);
    }
}
