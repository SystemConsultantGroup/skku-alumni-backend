package com.scg.alumni.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 사진 주소가 "올릴 때 쓴 호스트" 에 묶이지 않는지 지킨다.
 *
 * <p>절대 주소를 저장하면 운영 도메인이 바뀌거나 사무처가 내부 주소로 한 번
 * 올린 순간, 그 사진은 다른 사람 화면에서 영영 깨진 채로 남는다.
 */
class StoredImageUrlTest {

    @Test
    @DisplayName("업로드 응답의 절대 주소를 경로로 바꾼다")
    void absoluteUrlBecomesPath() {
        assertThat(StoredImageUrl.toStoredPath("http://localhost:9090/api/v1/profile-images/a.png"))
                .isEqualTo("/api/v1/profile-images/a.png");
        assertThat(StoredImageUrl.toStoredPath("https://alumni.scg.skku.ac.kr/api/v1/post-images/b.jpg"))
                .isEqualTo("/api/v1/post-images/b.jpg");
    }

    @Test
    @DisplayName("이미 경로면 그대로 둔다")
    void pathStaysAsIs() {
        assertThat(StoredImageUrl.toStoredPath("/api/v1/post-images/b.jpg"))
                .isEqualTo("/api/v1/post-images/b.jpg");
    }

    @Test
    @DisplayName("본문에 이미지가 여럿이고 호스트가 섞여 있어도 모두 바꾼다")
    void everyImageInBodyIsRewritten() {
        String body = """
                ## 안내

                ![포스터](http://127.0.0.1:9090/api/v1/post-images/a.png)
                ![지도](https://test.alumni.scg.skku.ac.kr/api/v1/post-images/b.png)
                ![이미 경로](/api/v1/post-images/c.png)
                """;

        assertThat(StoredImageUrl.toStoredPath(body))
                .contains("![포스터](/api/v1/post-images/a.png)")
                .contains("![지도](/api/v1/post-images/b.png)")
                .contains("![이미 경로](/api/v1/post-images/c.png)")
                .doesNotContain("127.0.0.1")
                .doesNotContain("test.alumni.scg.skku.ac.kr");
    }

    @Test
    @DisplayName("우리 이미지가 아닌 바깥 주소는 건드리지 않는다")
    void externalLinksAreUntouched() {
        String body = "자세히는 [모교 홈페이지](https://www.skku.edu/news) 를 보세요.";
        assertThat(StoredImageUrl.toStoredPath(body)).isEqualTo(body);
        assertThat(StoredImageUrl.toStoredPath("https://cdn.example.com/photo.png"))
                .isEqualTo("https://cdn.example.com/photo.png");
    }

    @Test
    @DisplayName("빈 값은 그대로 통과시킨다")
    void blankPassesThrough() {
        assertThat(StoredImageUrl.toStoredPath(null)).isNull();
        assertThat(StoredImageUrl.toStoredPath("")).isEmpty();
    }
}
