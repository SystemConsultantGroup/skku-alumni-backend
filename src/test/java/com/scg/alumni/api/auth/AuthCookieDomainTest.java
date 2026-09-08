package com.scg.alumni.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.scg.alumni.global.security.AuthProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 웹(alumni.scg.skku.ac.kr)과 API(api.alumni.scg.skku.ac.kr)는 호스트가 다르다.
 *
 * <p>인증 쿠키에 도메인을 주지 않으면 쿠키가 API 호스트에만 붙어, 웹의 서버 컴포넌트가
 * API 를 부를 때 인증이 빠진 요청이 된다. 공지·커뮤니티·비즈니스 목록이 로그인한
 * 사용자에게도 늘 비어 보이던 원인이었다.
 */
class AuthCookieDomainTest {

    private AuthController controllerWithDomain(String domain) {
        AuthProperties properties = new AuthProperties();
        properties.setCookieDomain(domain);
        // 쿠키 조립만 확인하므로 나머지 협력자는 쓰이지 않는다.
        return new AuthController(null, null, null, null, properties, null);
    }

    private List<String> setCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE);
    }

    private void addCookie(AuthController controller, MockHttpServletResponse response) {
        ReflectionTestUtils.invokeMethod(controller, "addCookie",
                response, "member_access_token", "token-value", Duration.ofHours(2), true);
    }

    @Test
    @DisplayName("도메인을 설정하면 부모 도메인 쿠키로 내려가 웹과 API 가 같은 세션을 본다")
    void 도메인을_설정하면_부모_도메인으로_내려간다() {
        AuthController controller = controllerWithDomain("alumni.scg.skku.ac.kr");
        MockHttpServletResponse response = new MockHttpServletResponse();

        addCookie(controller, response);

        assertThat(setCookies(response))
                .anyMatch(header -> header.contains("member_access_token=token-value")
                        && header.contains("Domain=alumni.scg.skku.ac.kr"));
    }

    @Test
    @DisplayName("도메인을 붙일 때 예전 호스트 전용 쿠키를 함께 만료시킨다")
    void 예전_호스트_전용_쿠키를_함께_지운다() {
        AuthController controller = controllerWithDomain("alumni.scg.skku.ac.kr");
        MockHttpServletResponse response = new MockHttpServletResponse();

        addCookie(controller, response);

        // 이름이 같고 범위만 다른 쿠키가 둘 남으면 브라우저가 둘 다 보내고 서버는
        // 어느 것을 볼지 알 수 없다. 새 쿠키와 함께 옛 쿠키 만료 헤더가 나가야 한다.
        assertThat(setCookies(response))
                .anyMatch(header -> header.startsWith("member_access_token=;")
                        && !header.contains("Domain="));
    }

    @Test
    @DisplayName("로컬처럼 도메인이 비면 호스트 전용 쿠키 하나만 내려간다")
    void 도메인이_비면_호스트_전용_하나만_내려간다() {
        AuthController controller = controllerWithDomain("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        addCookie(controller, response);

        // localhost 에 Domain 을 주면 브라우저가 쿠키를 아예 거부한다.
        assertThat(setCookies(response)).hasSize(1);
        assertThat(setCookies(response).get(0)).doesNotContain("Domain=");
    }

    @Test
    @DisplayName("로그아웃은 도메인 쿠키와 호스트 전용 쿠키를 모두 지운다")
    void 로그아웃은_두_범위를_모두_지운다() {
        AuthController controller = controllerWithDomain("alumni.scg.skku.ac.kr");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ReflectionTestUtils.invokeMethod(controller, "clearCookie",
                response, "member_access_token", true);

        List<String> headers = setCookies(response);
        assertThat(headers).hasSize(2);
        assertThat(headers).anyMatch(header -> header.contains("Domain=alumni.scg.skku.ac.kr"));
        assertThat(headers).anyMatch(header -> !header.contains("Domain="));
    }
}
