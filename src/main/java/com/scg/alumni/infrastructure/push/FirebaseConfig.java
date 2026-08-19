package com.scg.alumni.infrastructure.push;

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
 * <p>자격 증명이 없으면 애플리케이션을 띄우지 못하게 하는 대신 발송만 꺼진다.
 * 로컬 개발과 테스트에서 Firebase 없이 서버를 돌릴 수 있어야 하기 때문이다.
 * 운영 환경에 값을 넣지 않으면 알림이 조용히 안 나가므로, 기동 로그에 경고를 남긴다.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    /** 서비스 계정 JSON의 경로. 파일 대신 JSON 본문을 그대로 넣어도 된다. */
    @Value("${firebase.credentials:}")
    private String credentials;

    @Bean
    public FirebaseApp firebaseApp() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        try (InputStream stream = openCredentials()) {
            if (stream == null) {
                log.warn("firebase.credentials 가 없어 푸시 알림이 비활성화됩니다. 운영 환경이라면 설정을 확인하세요.");
                return null;
            }
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build();
            log.info("Firebase 자격 증명을 불러왔습니다. 푸시 알림이 활성화됩니다.");
            return FirebaseApp.initializeApp(options);
        } catch (IOException exception) {
            log.error("Firebase 자격 증명을 읽지 못해 푸시 알림이 비활성화됩니다.", exception);
            return null;
        }
    }

    private InputStream openCredentials() throws IOException {
        if (!StringUtils.hasText(credentials)) {
            return null;
        }
        String value = credentials.trim();
        if (value.startsWith("{")) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
        Path path = Path.of(value);
        if (!Files.exists(path)) {
            log.error("firebase.credentials 경로에 파일이 없습니다: {}", path);
            return null;
        }
        return Files.newInputStream(path);
    }
}
