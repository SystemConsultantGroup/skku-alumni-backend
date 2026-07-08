package com.scg.alumni.global.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;

@Validated
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    @DurationUnit(HOURS)
    private Duration accessTokenTtl = Duration.ofHours(2);

    @DurationUnit(DAYS)
    private Duration refreshTokenTtl = Duration.ofDays(7);

    @NotBlank
    private String jwtSecret = "local-development-secret-for-skku-alumni-auth-change-me";

    @NotBlank
    private String memberAccessCookieName = "member_access_token";

    @NotBlank
    private String memberRefreshCookieName = "member_refresh_token";

    @NotBlank
    private String adminAccessCookieName = "admin_access_token";

    @NotBlank
    private String adminRefreshCookieName = "admin_refresh_token";

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getMemberAccessCookieName() {
        return memberAccessCookieName;
    }

    public void setMemberAccessCookieName(String memberAccessCookieName) {
        this.memberAccessCookieName = memberAccessCookieName;
    }

    public String getMemberRefreshCookieName() {
        return memberRefreshCookieName;
    }

    public void setMemberRefreshCookieName(String memberRefreshCookieName) {
        this.memberRefreshCookieName = memberRefreshCookieName;
    }

    public String getAdminAccessCookieName() {
        return adminAccessCookieName;
    }

    public void setAdminAccessCookieName(String adminAccessCookieName) {
        this.adminAccessCookieName = adminAccessCookieName;
    }

    public String getAdminRefreshCookieName() {
        return adminRefreshCookieName;
    }

    public void setAdminRefreshCookieName(String adminRefreshCookieName) {
        this.adminRefreshCookieName = adminRefreshCookieName;
    }

    public String accessCookieName(AuthScope scope) {
        return scope == AuthScope.ADMIN ? adminAccessCookieName : memberAccessCookieName;
    }

    public String refreshCookieName(AuthScope scope) {
        return scope == AuthScope.ADMIN ? adminRefreshCookieName : memberRefreshCookieName;
    }
}
