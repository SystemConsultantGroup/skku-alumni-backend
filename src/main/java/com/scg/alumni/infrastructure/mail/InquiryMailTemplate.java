package com.scg.alumni.infrastructure.mail;

import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * 문의 접수 알림 메일의 모양.
 *
 * <p>메일 클라이언트는 외부 CSS 와 최신 레이아웃을 믿을 수 없다. 표(table)와 인라인 스타일만
 * 쓰고, 버튼도 링크에 배경을 입힌 형태로 만든다. 사용자가 쓴 값은 전부 이스케이프해서
 * 넣는다 — 제목에 태그를 적어 보내는 문의가 사무처 메일함에서 그대로 렌더링되면 안 된다.
 */
public final class InquiryMailTemplate {

    private static final String BRAND = "#047857";

    private InquiryMailTemplate() {
    }

    /** 메일에 담을 문의 내용. */
    public record Model(
            long inquiryId,
            String categoryLabel,
            String title,
            String body,
            String memberName,
            String studentId,
            String phone,
            String email,
            String submittedAt,
            List<Attachment> attachments,
            String replyUrl
    ) {
    }

    /**
     * @param previewCid 메일에 인라인으로 실은 미리보기 이미지의 Content-ID. 없으면 이름만 보인다.
     */
    public record Attachment(String name, long size, String previewCid) {
        public Attachment(String name, long size) {
            this(name, size, null);
        }
    }

    public static String subject(Model model) {
        // 줄바꿈이 섞이면 메일 헤더가 깨진다(헤더 주입). 제목은 한 줄로 만든다.
        return "[문의가 도착했습니다] " + model.title().replaceAll("[\\r\\n]+", " ");
    }

    public static String html(Model model) {
        StringBuilder rows = new StringBuilder();
        row(rows, "분류", model.categoryLabel());
        row(rows, "작성자", model.memberName());
        row(rows, "학번", model.studentId());
        row(rows, "연락처", model.phone());
        row(rows, "이메일", model.email());
        row(rows, "접수 시각", model.submittedAt());

        StringBuilder files = new StringBuilder();
        if (model.attachments().isEmpty()) {
            files.append("<span style=\"color:#71717a;\">첨부 파일 없음</span>");
        } else {
            for (Attachment attachment : model.attachments()) {
                files.append("<div style=\"padding:8px 0;border-bottom:1px solid #f4f4f5;\">")
                        .append(InquiryImagePreview.isVideo(attachment.name()) ? "&#127916; " : "&#128206; ")
                        .append(esc(attachment.name()))
                        .append(" <span style=\"color:#71717a;\">(").append(esc(formatSize(attachment.size()))).append(")</span>");
                if (attachment.previewCid() != null) {
                    // 누르면 관리자 화면의 문의로 간다. 거기서 원본을 크게 보거나 내려받는다.
                    files.append("<div style=\"margin-top:8px;\"><a href=\"").append(esc(model.replyUrl())).append("\">")
                            .append("<img src=\"cid:").append(esc(attachment.previewCid())).append("\" alt=\"")
                            .append(esc(attachment.name()))
                            .append("\" style=\"display:block;max-width:100%;height:auto;border:1px solid #e4e4e7;border-radius:8px;\">")
                            .append("</a></div>");
                } else if (InquiryImagePreview.isVideo(attachment.name())) {
                    // 메일 클라이언트는 영상을 재생하지 못한다. 재생은 관리자 화면에서 한다.
                    files.append("<div style=\"margin-top:6px;font-size:12px;color:#71717a;\">")
                            .append("영상은 메일에서 재생되지 않습니다. 답변하기를 눌러 관리자 화면에서 재생하세요.</div>");
                }
                files.append("</div>");
            }
        }

        return """
                <!doctype html>
                <html lang="ko">
                <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background:#f4f4f5;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f5;padding:24px 12px;">
                  <tr><td align="center">
                    <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e4e4e7;font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',Arial,sans-serif;">
                      <tr><td style="background:%1$s;padding:28px 32px;">
                        <div style="color:#a7f3d0;font-size:12px;font-weight:700;letter-spacing:0.5px;">성균관대학교 총동창회</div>
                        <div style="color:#ffffff;font-size:24px;font-weight:800;margin-top:6px;">문의가 도착했습니다</div>
                      </td></tr>
                      <tr><td style="padding:28px 32px 8px 32px;">
                        <div style="font-size:12px;font-weight:700;color:%1$s;">문의 제목</div>
                        <div style="font-size:20px;font-weight:800;color:#18181b;margin-top:4px;line-height:1.4;">%2$s</div>
                      </td></tr>
                      <tr><td style="padding:12px 32px;">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="font-size:14px;color:#3f3f46;">%3$s</table>
                      </td></tr>
                      <tr><td style="padding:8px 32px;">
                        <div style="font-size:12px;font-weight:700;color:%1$s;margin-bottom:8px;">문의 내용</div>
                        <div style="background:#fafafa;border:1px solid #e4e4e7;border-radius:12px;padding:16px;font-size:15px;line-height:1.7;color:#27272a;white-space:pre-wrap;word-break:break-word;">%4$s</div>
                      </td></tr>
                      <tr><td style="padding:16px 32px 8px 32px;">
                        <div style="font-size:12px;font-weight:700;color:%1$s;margin-bottom:4px;">첨부 파일</div>
                        <div style="font-size:14px;color:#3f3f46;">%5$s</div>
                      </td></tr>
                      <tr><td align="center" style="padding:28px 32px 12px 32px;">
                        <a href="%6$s" style="display:inline-block;background:%1$s;color:#ffffff;font-size:16px;font-weight:800;text-decoration:none;padding:14px 40px;border-radius:999px;">답변하기</a>
                      </td></tr>
                      <tr><td align="center" style="padding:0 32px 28px 32px;font-size:12px;color:#a1a1aa;line-height:1.6;">
                        버튼을 누르면 관리자 사이트의 이 문의 화면으로 이동합니다. 로그인이 필요하면 로그인 뒤에 바로 열립니다.<br>
                        원본 첨부 파일은 관리자 화면에서 크게 보거나 내려받을 수 있습니다.
                      </td></tr>
                    </table>
                    <div style="font-size:11px;color:#a1a1aa;margin-top:16px;font-family:Arial,sans-serif;">문의 #%7$d · 이 메일은 발신 전용입니다.</div>
                  </td></tr>
                </table>
                </body>
                </html>
                """.formatted(BRAND, esc(model.title()), rows, esc(model.body()), files,
                esc(model.replyUrl()), model.inquiryId());
    }

    /** HTML 을 못 보는 클라이언트용. */
    public static String text(Model model) {
        StringBuilder text = new StringBuilder();
        text.append("[문의가 도착했습니다]\n\n")
                .append("제목: ").append(model.title()).append('\n')
                .append("분류: ").append(model.categoryLabel()).append('\n')
                .append("작성자: ").append(nullToDash(model.memberName())).append('\n')
                .append("학번: ").append(nullToDash(model.studentId())).append('\n')
                .append("연락처: ").append(nullToDash(model.phone())).append('\n')
                .append("이메일: ").append(nullToDash(model.email())).append('\n')
                .append("접수 시각: ").append(model.submittedAt()).append("\n\n")
                .append(model.body()).append("\n\n");
        if (!model.attachments().isEmpty()) {
            text.append("첨부 파일\n");
            model.attachments().forEach(a -> text.append(" - ").append(a.name()).append(" (")
                    .append(formatSize(a.size())).append(")\n"));
            text.append('\n');
        }
        text.append("답변하기: ").append(model.replyUrl()).append('\n');
        return text.toString();
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void row(StringBuilder rows, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        rows.append("<tr><td style=\"padding:4px 0;width:84px;color:#71717a;\">").append(label)
                .append("</td><td style=\"padding:4px 0;font-weight:600;\">").append(esc(value)).append("</td></tr>");
    }

    private static String esc(String value) {
        // 인코딩을 주지 않으면 ISO-8859-1 기준이라 가운뎃점(·) 같은 글자가 엔티티로 바뀐다.
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
