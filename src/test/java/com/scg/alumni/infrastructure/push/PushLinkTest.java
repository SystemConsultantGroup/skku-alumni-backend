package com.scg.alumni.infrastructure.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PushLinkTest {

    @Test
    void blankMeansNoLink() {
        assertThat(PushLink.parse(null)).isNull();
        assertThat(PushLink.parse("   ")).isNull();
    }

    /** 앱은 자기 시작 주소에 경로를 붙인다. 호스트를 실어 보내면 테스트 주소가 운영 앱에서 열린다. */
    @Test
    void memberSiteAddressOpensInsideTheApp() {
        PushLink link = PushLink.parse("  https://alumni.scg.skku.ac.kr/community/club/7/posts/99  ");

        assertThat(link.payload()).isEqualTo(Map.of("path", "/community/club/7/posts/99"));
        assertThat(link.originalUrl()).isEqualTo("https://alumni.scg.skku.ac.kr/community/club/7/posts/99");
    }

    @Test
    void testSiteAddressKeepsQueryAndFragment() {
        PushLink link = PushLink.parse("https://test.alumni.scg.skku.ac.kr/members?keyword=%EA%B9%80#top");

        assertThat(link.path()).isEqualTo("/members?keyword=%EA%B9%80#top");
    }

    @Test
    void memberSiteRootOpensHome() {
        assertThat(PushLink.parse("https://alumni.scg.skku.ac.kr").path()).isEqualTo("/");
    }

    @Test
    void otherSitesOpenInTheBrowser() {
        PushLink link = PushLink.parse("https://forms.gle/abc123");

        assertThat(link.payload()).isEqualTo(Map.of("url", "https://forms.gle/abc123"));
    }

    @Test
    void adminSiteIsRejected() {
        assertThatThrownBy(() -> PushLink.parse("https://admin.alumni.scg.skku.ac.kr/members/3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("관리자 사이트 주소");
        assertThatThrownBy(() -> PushLink.parse("https://admin.scg.skku.ac.kr/notices"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 앱이 알림 값으로 WebView 를 옮기므로, 스크립트나 앱 전용 스킴은 들어오면 안 된다. */
    @Test
    void nonWebSchemesAreRejected() {
        assertThatThrownBy(() -> PushLink.parse("javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushLink.parse("intent://scan#Intent;end")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushLink.parse("alumni.scg.skku.ac.kr/notices/1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushLink.parse("https://alumni.scg.skku.ac.kr/a b")).isInstanceOf(IllegalArgumentException.class);
    }
}
