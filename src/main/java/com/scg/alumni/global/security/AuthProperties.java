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

    /**
     * 인증 쿠키를 붙일 도메인. 비우면 쿠키를 발급한 호스트에만 붙는다(호스트 전용).
     *
     * <p>웹은 alumni.scg.skku.ac.kr, API 는 api.alumni.scg.skku.ac.kr 로 호스트가 다르다.
     * 호스트 전용이면 브라우저가 웹 쪽 요청에 쿠키를 싣지 않아, 서버 컴포넌트가 API 를
     * 부를 때 인증이 없는 요청이 된다. 공지·커뮤니티·비즈니스 목록이 늘 비어 있던 원인이다.
     * 부모 도메인을 지정하면 두 호스트가 같은 세션을 본다.
     *
     * <p>로컬은 localhost 하나뿐이라 비워 둔다 — localhost 에 도메인을 지정하면
     * 브라우저가 쿠키를 아예 거부한다.
     */
    private String cookieDomain = "";

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

    public String getCookieDomain() {
        return cookieDomain;
    }

    public void setCookieDomain(String cookieDomain) {
        this.cookieDomain = cookieDomain;
    }

    public String accessCookieName(AuthScope scope) {
        return scope == AuthScope.ADMIN ? adminAccessCookieName : memberAccessCookieName;
    }

    public String refreshCookieName(AuthScope scope) {
        return scope == AuthScope.ADMIN ? adminRefreshCookieName : memberRefreshCookieName;
    }
}
