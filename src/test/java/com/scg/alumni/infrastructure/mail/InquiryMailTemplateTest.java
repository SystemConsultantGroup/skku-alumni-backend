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
}
