package com.scg.alumni.infrastructure.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 개발 환경에 운영 프로젝트의 서비스 계정이 들어가면 개발자가 시험 삼아 올린 공지가
 * 실제 임원들에게 나간다. 그 사고를 기동 실패로 바꿔 놓았는지 확인한다.
 */
class FirebaseConfigTest {

    /** 실제 비밀 키가 아니어도 project_id 대조는 그 전에 끝난다. */
    private static String credentialsOf(String projectId) {
        return "{\"type\":\"service_account\",\"project_id\":\"" + projectId + "\"}";
    }

    private FirebaseConfig config(String credentials, String expectedProjectId) {
        FirebaseConfig config = new FirebaseConfig();
        ReflectionTestUtils.setField(config, "credentials", credentials);
        ReflectionTestUtils.setField(config, "expectedProjectId", expectedProjectId);
        return config;
    }

    @Test
    @DisplayName("개발 환경에 운영 프로젝트 자격 증명이 들어오면 기동을 멈춘다")
    void 다른_프로젝트의_자격_증명이면_기동을_멈춘다() {
        FirebaseConfig config = config(credentialsOf("skku-alumni-37619"), "skku-alumni-dev");

        assertThatThrownBy(config::firebaseApp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skku-alumni-37619")
                .hasMessageContaining("skku-alumni-dev");
    }

    @Test
    @DisplayName("자격 증명이 아예 없으면 발송만 꺼지고 기동은 계속된다")
    void 자격_증명이_없으면_발송만_꺼진다() {
        FirebaseConfig config = config("", "skku-alumni-37619");

        assertThatCode(config::firebaseApp).doesNotThrowAnyException();
        assertThat(config.firebaseApp()).isNull();
    }

    @Test
    @DisplayName("서비스 계정 JSON 이 아니면(project_id 없음) 기동을 멈춘다")
    void project_id_가_없으면_기동을_멈춘다() {
        FirebaseConfig config = config("{\"hello\":\"world\"}", "skku-alumni-37619");

        assertThatThrownBy(config::firebaseApp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project_id");
    }
}
