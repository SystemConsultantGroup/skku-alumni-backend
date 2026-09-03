package com.scg.alumni.api.common;

import java.util.regex.Pattern;

/**
 * 이미지 주소를 저장 형태(경로)로 맞춘다.
 *
 * <p>업로드 응답은 요청이 들어온 호스트로 절대 주소를 만든다. 그 값을 그대로
 * 저장하면 사진이 "올릴 때 쓴 주소"에 영영 묶인다. 사무처가 내부 주소로 한 번
 * 올리거나 운영 도메인이 바뀌면, 다른 사람 화면에서는 깨진 채로 남는다.
 * 게시글은 본문 마크다운에 주소가 박히므로 더 그렇다.
 *
 * <p>그래서 저장은 언제나 경로(/api/v1/...)로 하고, 주소는 화면이 볼 때 붙인다.
 */
public final class StoredImageUrl {

    private static final Pattern ABSOLUTE_MEDIA = Pattern.compile(
            "https?://[^/\\s)\"']+(/api/v1/(?:profile-images|post-images)/)",
            Pattern.CASE_INSENSITIVE);

    private StoredImageUrl() {
    }

    /**
     * 절대 주소를 경로로 바꾼다. 이미 경로면 그대로 둔다.
     *
     * <p>본문 마크다운을 통째로 넘겨도 된다 — 안에 있는 이미지 주소를 모두 바꾼다.
     */
    public static String toStoredPath(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return ABSOLUTE_MEDIA.matcher(value).replaceAll("$1");
    }
}
