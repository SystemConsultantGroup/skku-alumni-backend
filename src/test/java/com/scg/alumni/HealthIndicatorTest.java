package com.scg.alumni;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 메일 서버는 건강 지표에 들어가면 안 된다.
 *
 * <p>starter-mail 은 /actuator/health 호출마다 SMTP 에 로그인해 본다. 메일 설정이 비었거나 메일
 * 서버가 느리면 서비스 전체가 DOWN(503)으로 보인다. 문의 알림 메일은 부가 기능이다(2026-10-06
 * 배포에서 MAIL_* 가 비어 있어 종합 헬스가 503 이 됐다).
 *
 * <p>테스트 리소스에 별도 application.yml 이 있어 main 의 설정은 테스트에서 읽히지 않는다.
 * 그래서 둘로 나눠 본다. 속성 키가 실제로 지표를 끄는지, main 설정에 그 값이 들어 있는지.
 */
class HealthIndicatorTest {

    /** 메일 발송기가 있는데 속성을 안 주면 지표가 생긴다 — 이 전제가 아래 두 테스트의 의미다. */
    @Nested
    @SpringBootTest(properties = "spring.mail.host=localhost")
    class WithoutTheSwitch {
        @Autowired
        ApplicationContext context;

        @Test
        void indicatorExistsByDefault() {
            assertThat(context.getBeanNamesForType(JavaMailSender.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(MailHealthIndicator.class)).isNotEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {"spring.mail.host=localhost", "management.health.mail.enabled=false"})
    class WithTheSwitch {
        @Autowired
        ApplicationContext context;

        @Test
        void switchRemovesTheIndicator() {
            assertThat(context.getBeanNamesForType(JavaMailSender.class)).isNotEmpty();
            assertThat(context.getBeanNamesForType(MailHealthIndicator.class)).isEmpty();
        }
    }

    @Test
    void mainConfigurationTurnsTheSwitchOff() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("main", new FileSystemResource("src/main/resources/application.yml"));
        Object value = sources.stream()
                .map(source -> source.getProperty("management.health.mail.enabled"))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        assertThat(value).isEqualTo(false);
    }
}
