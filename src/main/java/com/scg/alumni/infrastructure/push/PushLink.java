package com.scg.alumni.infrastructure.push;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 알림을 눌렀을 때 열 곳.
 *
 * <p>사무처는 경로를 조립하지 않는다. 브라우저 주소창의 주소를 그대로 붙여 넣는다.
 * 그 주소를 앱이 이해하는 두 갈래로 나눈다.
 *
 * <ul>
 *   <li>회원 사이트 주소(alumni.scg.skku.ac.kr) → 앱 안에서 그 화면을 연다. 호스트는 버리고
 *       경로만 보낸다. 앱은 자기 시작 주소(운영/테스트)에 경로를 붙이므로, 테스트 사이트
 *       주소를 붙여 넣어도 운영 앱에서는 운영 화면이 열린다.
 *   <li>그 밖의 https 주소(행사 신청서 등) → 앱 밖의 브라우저로 연다. 앱 WebView 에서
 *       남의 사이트를 열면 돌아올 길이 없다.
 * </ul>
 *
 * <p>관리자 사이트 주소는 받지 않는다. 회원 앱에서는 열리지 않는 화면인데, 관리 화면을
 * 보다가 주소를 복사해 붙이는 실수가 가장 흔할 자리다. 조용히 바깥 브라우저로 보내면
 * 회원이 관리자 로그인 화면을 보게 된다.
 */
public record PushLink(String originalUrl, String path, String externalUrl) {

    private static final Set<String> MEMBER_WEB_HOSTS = Set.of("alumni.scg.skku.ac.kr", "test.alumni.scg.skku.ac.kr");
    private static final String SERVICE_DOMAIN = "scg.skku.ac.kr";

    /** 비어 있으면 null. 알림을 누르면 지금처럼 홈이 열린다. */
    public static PushLink parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("웹 주소 형식이 올바르지 않습니다. 브라우저 주소창의 주소를 그대로 붙여 넣어 주세요.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("https:// 로 시작하는 웹 주소만 넣을 수 있습니다.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("웹 주소에 사이트 이름이 없습니다. 주소를 다시 확인해 주세요.");
        }
        if (MEMBER_WEB_HOSTS.contains(host)) {
            return new PushLink(value, pathOf(uri), null);
        }
        if (host.endsWith(SERVICE_DOMAIN) && host.contains("admin")) {
            throw new IllegalArgumentException(
                    "관리자 사이트 주소는 회원 앱에서 열 수 없습니다. 회원 사이트(https://alumni.scg.skku.ac.kr)에서 같은 화면을 열고 그 주소를 붙여 넣어 주세요.");
        }
        return new PushLink(value, null, value);
    }

    /** 앱에 실어 보낼 값. 앱은 path 가 있으면 안에서, url 이 있으면 바깥 브라우저로 연다. */
    public Map<String, String> payload() {
        return path != null ? Map.of("path", path) : Map.of("url", externalUrl);
    }

    /** 이미 인코딩된 형태 그대로 둔다. 앱이 다시 인코딩하지 않고 시작 주소 뒤에 붙인다. */
    private static String pathOf(URI uri) {
        String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "/";
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        if (uri.getRawFragment() != null) {
            path += "#" + uri.getRawFragment();
        }
        return path;
    }
}
