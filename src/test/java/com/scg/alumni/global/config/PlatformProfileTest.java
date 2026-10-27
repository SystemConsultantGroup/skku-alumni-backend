package com.scg.alumni.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.scg.alumni.global.security.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfigurationSource;

class PlatformProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ProfileConfiguration.class);

    @Test
    void platformOverridesProductionUrlsAndCookieNames() {
        runner.withPropertyValues("spring.profiles.active=prod,platform").run(context -> {
            assertThat(context).hasNotFailed();
            AuthProperties auth = context.getBean(AuthProperties.class);
            assertThat(auth.getMemberWebUrl()).isEqualTo("https://test.alumni.scg.skku.ac.kr");
            assertThat(auth.getAdminWebUrl()).isEqualTo("https://admin.test.alumni.scg.skku.ac.kr");
            assertThat(auth.getCookieDomain()).isEqualTo("test.alumni.scg.skku.ac.kr");
            assertThat(auth.getMemberAccessCookieName()).isEqualTo("platform_member_access_token");
            assertThat(auth.getMemberRefreshCookieName()).isEqualTo("platform_member_refresh_token");
            assertThat(auth.getAdminAccessCookieName()).isEqualTo("platform_admin_access_token");
            assertThat(auth.getAdminRefreshCookieName()).isEqualTo("platform_admin_refresh_token");

            var request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/member/login");
            var cors = context.getBean(CorsConfigurationSource.class).getCorsConfiguration(request);
            assertThat(cors).isNotNull();
            assertThat(cors.getAllowCredentials()).isTrue();
            assertThat(cors.checkOrigin("https://test.alumni.scg.skku.ac.kr")).isNotNull();
            assertThat(cors.checkOrigin("https://admin.test.alumni.scg.skku.ac.kr")).isNotNull();
            assertThat(cors.checkOrigin("https://untrusted.example")).isNull();
        });
    }

    @Test
    void productionWithoutPlatformKeepsExistingSettings() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            AuthProperties auth = context.getBean(AuthProperties.class);
            assertThat(auth.getMemberWebUrl()).isEqualTo("https://alumni.scg.skku.ac.kr");
            assertThat(auth.getAdminWebUrl()).isEqualTo("https://admin.alumni.scg.skku.ac.kr");
            assertThat(auth.getCookieDomain()).isEqualTo("alumni.scg.skku.ac.kr");
            assertThat(auth.getMemberAccessCookieName()).isEqualTo("member_access_token");
            assertThat(auth.getAdminAccessCookieName()).isEqualTo("admin_access_token");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    @Import(CorsConfig.class)
    static class ProfileConfiguration {
    }
}
