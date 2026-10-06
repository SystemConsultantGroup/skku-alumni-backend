package com.scg.alumni.infrastructure.mail;

import com.scg.alumni.domain.inquiry.InquiryCategory;
import com.scg.alumni.global.security.AuthProperties;
import jakarta.mail.internet.MimeMessage;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 문의가 접수되면 사무처가 정한 메일 주소들로 알린다.
 *
 * <p>메일이 나가지 않아도 문의는 접수돼야 한다. 그래서 요청과 분리해 비동기로 보내고,
 * 실패는 로그로만 남긴다. 주소마다 따로 보낸다 — 한 통에 모아 보내면 주소 하나가 틀렸을 때
 * 전부 반송된다.
 *
 * <p>SMTP 설정이 없으면(로컬·테스트) 보내지 않고 경고만 남긴다.
 */
@Slf4j
@Service
public class InquiryMailService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final AuthProperties authProperties;
    private final InquiryImagePreview imagePreview;
    private final String mailHost;
    private final String from;

    public InquiryMailService(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<JavaMailSender> mailSender,
            AuthProperties authProperties,
            InquiryImagePreview imagePreview,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${inquiry.mail-from:scg@scg.skku.ac.kr}") String from) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailSender = mailSender;
        this.authProperties = authProperties;
        this.imagePreview = imagePreview;
        this.mailHost = mailHost;
        this.from = from;
    }

    /** 접수된 문의 한 건을 알린다. 문의 내용은 저장된 것을 다시 읽어 쓴다. */
    @Async
    public void notifyReceived(long inquiryId) {
        try {
            send(inquiryId);
        } catch (RuntimeException exception) {
            log.error("문의 알림 메일 준비에 실패했습니다. inquiryId={}", inquiryId, exception);
        }
    }

    private void send(long inquiryId) {
        List<String> recipients = jdbcTemplate.queryForList(
                "select email from inquiry_notify_emails order by id", String.class);
        if (recipients.isEmpty()) {
            log.info("문의 알림을 받을 메일 주소가 등록되지 않아 보내지 않습니다. inquiryId={}", inquiryId);
            return;
        }
        if (mailHost == null || mailHost.isBlank() || mailSender.getIfAvailable() == null) {
            log.warn("SMTP 가 설정되지 않아 문의 알림 메일 {}건을 보내지 못했습니다. inquiryId={}", recipients.size(), inquiryId);
            return;
        }

        Map<String, Object> inquiry = jdbcTemplate.queryForMap("""
                select i.title, i.body, i.category, i.created_at,
                       u.name, u.student_id, u.phone, u.email
                from inquiries i
                join users u on u.id = i.user_id
                where i.id = ?
                """, inquiryId);
        // 미리보기는 받는 사람마다 다시 만들 이유가 없다. 한 번 만들어 모든 수신자에게 싣는다.
        Map<String, byte[]> previews = new LinkedHashMap<>();
        List<InquiryMailTemplate.Attachment> attachments = new ArrayList<>();
        jdbcTemplate.query("""
                select id, original_name, object_name, size_bytes from inquiry_attachments where inquiry_id = ? order by id
                """, rs -> {
            String name = rs.getString("original_name");
            long size = rs.getLong("size_bytes");
            String cid = null;
            var thumbnail = imagePreview.thumbnail(name, rs.getString("object_name"), size);
            if (thumbnail.isPresent()) {
                cid = "attachment-" + rs.getLong("id");
                previews.put(cid, thumbnail.get());
            }
            attachments.add(new InquiryMailTemplate.Attachment(name, size, cid));
        }, inquiryId);

        InquiryMailTemplate.Model model = new InquiryMailTemplate.Model(
                inquiryId,
                InquiryCategory.labelOf((String) inquiry.get("category")),
                (String) inquiry.get("title"),
                (String) inquiry.get("body"),
                (String) inquiry.get("name"),
                (String) inquiry.get("student_id"),
                (String) inquiry.get("phone"),
                (String) inquiry.get("email"),
                formatTime(inquiry.get("created_at")),
                attachments,
                replyUrl(inquiryId));

        JavaMailSender sender = mailSender.getObject();
        for (String recipient : recipients) {
            try {
                MimeMessage message = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(from, "성균관대학교 총동창회");
                helper.setTo(recipient);
                helper.setSubject(InquiryMailTemplate.subject(model));
                helper.setText(InquiryMailTemplate.text(model), InquiryMailTemplate.html(model));
                // 인라인 파트는 본문을 지정한 뒤에 붙여야 같은 multipart/related 안에 들어간다.
                for (Map.Entry<String, byte[]> preview : previews.entrySet()) {
                    helper.addInline(preview.getKey(), new ByteArrayResource(preview.getValue()), "image/jpeg");
                }
                sender.send(message);
                log.info("문의 알림 메일을 보냈습니다. inquiryId={}", inquiryId);
            } catch (Exception exception) {
                log.error("문의 알림 메일 발송에 실패했습니다. inquiryId={}", inquiryId, exception);
            }
        }
    }

    /** 관리자 사이트의 이 문의 화면. 로그인 뒤 같은 화면으로 돌아오도록 경로만 쓴다. */
    String replyUrl(long inquiryId) {
        String base = authProperties.getAdminWebUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/inquiries/" + inquiryId;
    }

    private static String formatTime(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(TIME);
        }
        return value == null ? "" : value.toString();
    }
}
