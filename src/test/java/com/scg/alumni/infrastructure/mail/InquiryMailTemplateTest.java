package com.scg.alumni.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InquiryMailTemplateTest {

    private InquiryMailTemplate.Model model(String title, String body) {
        return new InquiryMailTemplate.Model(7L, "회비·납부", title, body, "김성균", "2020123456",
                "010-1234-5678", "kim@example.com", "2026-10-04 22:00",
                List.of(new InquiryMailTemplate.Attachment("견적서.pdf", 3 * 1024 * 1024)),
                "https://admin.alumni.scg.skku.ac.kr/inquiries/7");
    }

    /** 문의 내용은 사용자가 쓴 글이다. 태그를 적어 보내도 사무처 메일함에서 렌더링되면 안 된다. */
    @Test
    void userTextIsEscaped() {
        String html = InquiryMailTemplate.html(model("<script>alert(1)</script>", "<img src=x onerror=alert(1)>"));

        assertThat(html).doesNotContain("<script>alert(1)</script>").doesNotContain("<img src=x");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void containsTheDetailsAndTheReplyButton() {
        String html = InquiryMailTemplate.html(model("회비 문의", "이번 회비 금액이 궁금합니다."));

        assertThat(html).contains("문의가 도착했습니다", "회비 문의", "이번 회비 금액이 궁금합니다.", "회비·납부",
                "김성균", "견적서.pdf", "3.0 MB");
        assertThat(html).contains("href=\"https://admin.alumni.scg.skku.ac.kr/inquiries/7\"").contains("답변하기");
    }

    /** 제목에 줄바꿈을 넣어 메일 헤더를 덧붙이는 공격(헤더 주입)을 막는다. */
    @Test
    void subjectIsASingleLine() {
        assertThat(InquiryMailTemplate.subject(model("제목\r\nBcc: evil@example.com", "본문")))
                .doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void textVersionHasTheLink() {
        assertThat(InquiryMailTemplate.text(model("회비 문의", "본문")))
                .contains("https://admin.alumni.scg.skku.ac.kr/inquiries/7", "견적서.pdf");
    }

    private InquiryMailTemplate.Model modelWith(List<InquiryMailTemplate.Attachment> attachments) {
        return new InquiryMailTemplate.Model(7L, "기타·건의", "제목", "본문", "김성균", "2020123456",
                "010-1234-5678", "kim@example.com", "2026-10-04 22:00", attachments,
                "https://admin.alumni.scg.skku.ac.kr/inquiries/7");
    }

    /** 미리보기가 있는 이미지는 cid 로 본문에 끼워 넣고, 누르면 문의 화면으로 간다. */
    @Test
    void imagePreviewIsInlinedByContentId() {
        String html = InquiryMailTemplate.html(modelWith(List.of(
                new InquiryMailTemplate.Attachment("사진.jpg", 2048, "attachment-3"))));

        assertThat(html).contains("<img src=\"cid:attachment-3\"").contains("사진.jpg");
    }

    /** 영상은 메일에서 재생되지 않는다. 미리보기 대신 어디서 재생하는지 알려 준다. */
    @Test
    void videoGetsAHintInsteadOfAPlayer() {
        String html = InquiryMailTemplate.html(modelWith(List.of(
                new InquiryMailTemplate.Attachment("현장.mp4", 20 * 1024 * 1024))));

        assertThat(html).contains("현장.mp4", "영상은 메일에서 재생되지 않습니다").doesNotContain("<video").doesNotContain("<img");
    }

    /** 파일 이름은 올린 사람이 정한다. 이미지 태그의 속성으로 들어가도 빠져나오면 안 된다. */
    @Test
    void attachmentNameCannotBreakOutOfTheImageTag() {
        String html = InquiryMailTemplate.html(modelWith(List.of(
                new InquiryMailTemplate.Attachment("a\" onerror=\"alert(1).png", 10, "attachment-1"))));

        assertThat(html).doesNotContain("onerror=\"alert(1)").contains("&quot; onerror=&quot;alert(1)");
    }
}
