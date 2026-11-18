package com.scg.alumni.global.security;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
import com.scg.alumni.domain.access.AccessPermissionCache;
import com.scg.alumni.domain.access.Permission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deny by default for limited members. MySQL is authoritative on every request for now. */
@Component
@RequiredArgsConstructor
public class MemberPermissionFilter extends OncePerRequestFilter {
    private final AccessQualificationService qualificationService;
    private final AccessPermissionCache permissionCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if ("OPTIONS".equals(request.getMethod()) || authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)
                || principal.scope() != AuthScope.MEMBER || !request.getRequestURI().startsWith("/api/v1/")
                || (request.getRequestURI().startsWith("/api/v1/auth/")
                    && !request.getRequestURI().equals("/api/v1/auth/me")
                    && !request.getRequestURI().equals("/api/v1/auth/member/me"))) {
            chain.doFilter(request, response);
            return;
        }
        AccessLevel level;
        try {
            level = permissionCache.currentLevel(principal.id());
        } catch (RuntimeException failure) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        String path = request.getRequestURI();
        Permission required = requiredPermission(request.getMethod(), path);
        if (level == AccessLevel.LIMITED && "GET".equals(request.getMethod())
                && path.matches("/api/v1/profile-images/[a-f0-9-]+\\.[a-z]+")) {
            try {
                if (qualificationService.ownsProfileImage(principal.id(), path)) required = Permission.ID_CARD;
            } catch (RuntimeException failure) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        if (!level.permissions().contains(required)) {
            if (level == AccessLevel.LIMITED) {
                // Avoid an ERROR dispatch that can turn this response into 401.
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private Permission requiredPermission(String method, String path) {
        if ("GET".equals(method)) {
            if (path.equals("/api/v1/me") || path.equals("/api/v1/me/access")
                    || path.equals("/api/v1/auth/me") || path.equals("/api/v1/auth/member/me")) {
                return Permission.ID_CARD;
            }
            if (path.equals("/api/v1/notices") || path.matches("/api/v1/notices/[^/]+")) return Permission.READ_NOTICE;
        }
        if (("PATCH".equals(method) && path.equals("/api/v1/me"))
                || ("GET".equals(method) && path.equals("/api/v1/reference-data"))
                || ("POST".equals(method) && path.equals("/api/v1/profile-images"))) {
            return Permission.MANAGE_OWN_PROFILE;
        }
        if ("PATCH".equals(method) && (path.equals("/api/v1/me/password") || path.equals("/api/v1/me/login-id"))) {
            return Permission.MANAGE_CREDENTIALS;
        }
        return Permission.FULL_SERVICE;
    }
}
