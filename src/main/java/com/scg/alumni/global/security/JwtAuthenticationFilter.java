package com.scg.alumni.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AuthProperties authProperties;
    private final JwtTokenService jwtTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        resolveToken(request)
                .flatMap(jwtTokenService::parse)
                .ifPresent(this::authenticate);
        filterChain.doFilter(request, response);
    }

    private java.util.Optional<String> resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return java.util.Optional.of(authorization.substring(7));
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }

        boolean adminPath = request.getRequestURI().startsWith("/api/v1/admin/")
                || request.getRequestURI().startsWith("/api/v1/auth/admin/");
        String preferred = adminPath
                ? authProperties.getAdminAccessCookieName()
                : authProperties.getMemberAccessCookieName();
        String fallback = adminPath
                ? authProperties.getMemberAccessCookieName()
                : authProperties.getAdminAccessCookieName();

        // 두 자리를 함께 쓰는 주소가 있다. 프로필 사진과 글 이미지는 회원도 사무처도
        // 본다. 주소만 보고 회원 쿠키를 고르면 사무처 브라우저에는 아무 쿠키도 잡히지
        // 않아 깨진 이미지만 남는다. 없으면 다른 쪽 쿠키로 넘어간다.
        //
        // 넘어가도 위험하지 않다. 어디까지 허용할지는 SecurityConfig 가 권한으로
        // 정한다 — 회원 쿠키로 /api/v1/admin/** 에 들어가면 그대로 막힌다.
        return findCookie(cookies, preferred).or(() -> findCookie(cookies, fallback));
    }

    private java.util.Optional<String> findCookie(Cookie[] cookies, String name) {
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return java.util.Optional.ofNullable(cookie.getValue());
            }
        }
        return java.util.Optional.empty();
    }

    private void authenticate(AuthenticatedPrincipal principal) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(principal.roleName()))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
