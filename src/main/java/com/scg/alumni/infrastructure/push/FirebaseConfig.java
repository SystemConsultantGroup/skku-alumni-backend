package com.scg.alumni.infrastructure.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Firebase 자격 증명을 준비한다.
 *
 * <p>자격 증명이 <b>없으면</b> 애플리케이션을 띄우지 못하게 하는 대신 발송만 꺼진다.
 * 로컬 개발과 테스트에서 Firebase 없이 서버를 돌릴 수 있어야 하기 때문이다.
 * 운영 환경에 값을 넣지 않으면 알림이 조용히 안 나가므로, 기동 로그에 경고를 남긴다.
 *
 * <p>자격 증명이 <b>엉뚱한 프로젝트의 것이면</b> 이야기가 다르다. 개발 환경에 운영
 * 프로젝트의 서비스 계정이 들어가면 개발자가 시험 삼아 올린 공지가 실제 임원들의
 * 휴대전화로 나간다. 앱은 빌드 타입별로 Firebase 프로젝트가 갈려 있어
 * (release=skku-alumni-37619, debug=skku-alumni-dev) 토큰은 프로젝트 밖으로 나가지
 * 못하지만, 자격 증명을 바꿔 끼우면 그 울타리가 통째로 사라진다.
 * 그래서 기대하는 프로젝트를 명시해 두고 어긋나면 <b>기동을 실패시킨다</b> —
 * 조용히 넘어가면 사고가 되고, 여기서 죽으면 배포 실패로 끝난다.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    /** 서비스 계정 JSON의 경로. 파일 대신 JSON 본문을 그대로 넣어도 된다. */
    @Value("${firebase.credentials:}")
    private String credentials;

    /**
     * 이 환경이 써야 하는 Firebase 프로젝트 id.
     *
     * <p>비워 두면 대조하지 않고 실제 값만 로그에 남긴다. 로컬에서 아무 프로젝트나
     * 붙여 보는 경우를 막지 않으려는 것이고, 운영·개발 프로필에는 반드시 지정한다.
     */
    @Value("${firebase.expected-project-id:}")
    private String expectedProjectId;

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        byte[] raw = readCredentials();
        if (raw == null) {
            log.warn("firebase.credentials 가 없어 푸시 알림이 비활성화됩니다. 운영 환경이라면 설정을 확인하세요.");
            return null;
        }

        String projectId = readProjectId(raw);
        requireExpectedProject(projectId);

        try (InputStream stream = new ByteArrayInputStream(raw)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build();
            log.info("Firebase 자격 증명을 불러왔습니다. 푸시 알림이 활성화됩니다. project_id={}", projectId);
            return FirebaseApp.initializeApp(options);
        } catch (IOException exception) {
            log.error("Firebase 자격 증명을 읽지 못해 푸시 알림이 비활성화됩니다.", exception);
            return null;
        }
    }

    /**
     * 기대한 프로젝트가 아니면 기동을 멈춘다.
     *
     * <p>여기서 예외를 던지는 것이 이 클래스에서 유일하게 애플리케이션을 죽이는 길이다.
     * 알림이 안 나가는 것은 불편이지만, 다른 환경의 사용자에게 나가는 것은 사고다.
     */
    private void requireExpectedProject(String projectId) {
        if (!StringUtils.hasText(expectedProjectId)) {
            log.warn("firebase.expected-project-id 가 비어 있어 프로젝트 대조를 건너뜁니다. project_id={}", projectId);
            return;
        }
        if (!expectedProjectId.equals(projectId)) {
            throw new IllegalStateException(
                    "Firebase 자격 증명의 프로젝트가 이 환경과 다릅니다. 다른 환경의 사용자에게 알림이 나갈 수 있어 기동을 멈춥니다. "
                            + "expected=" + expectedProjectId + " actual=" + projectId);
        }
    }

    /** 자격 증명 JSON 에서 project_id 만 꺼낸다. 비밀 키는 건드리지 않는다. */
    private String readProjectId(byte[] raw) {
        try {
            JsonNode node = new ObjectMapper().readTree(raw);
            String projectId = node.path("project_id").asText(null);
            if (!StringUtils.hasText(projectId)) {
                throw new IllegalStateException("Firebase 자격 증명에 project_id 가 없습니다. 서비스 계정 JSON 이 맞는지 확인하세요.");
            }
            return projectId;
        } catch (IOException exception) {
            throw new IllegalStateException("Firebase 자격 증명을 JSON 으로 읽지 못했습니다.", exception);
        }
    }

    /** 경로로 주어졌든 JSON 본문으로 주어졌든 바이트로 읽어 온다. 없으면 null. */
    private byte[] readCredentials() {
        if (!StringUtils.hasText(credentials)) {
            return null;
        }
        String value = credentials.trim();
        if (value.startsWith("{")) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        Path path = Path.of(value);
        if (!Files.exists(path)) {
            log.error("firebase.credentials 경로에 파일이 없습니다: {}", path);
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            log.error("firebase.credentials 파일을 읽지 못했습니다: {}", path, exception);
            return null;
        }
    }
}
