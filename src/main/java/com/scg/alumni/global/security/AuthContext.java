package com.scg.alumni.global.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthContext {

    private AuthContext() {
    }

    public static AuthenticatedPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)) {
            throw new IllegalArgumentException("인증 정보가 없습니다.");
        }
        return principal;
    }

    public static Long currentMemberId() {
        AuthenticatedPrincipal principal = current();
        if (principal.scope() != AuthScope.MEMBER) {
            throw new IllegalArgumentException("회원 권한이 필요합니다.");
        }
        return principal.id();
    }

    public static Long currentMemberIdOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedPrincipal principal)
                || principal.scope() != AuthScope.MEMBER) {
            return null;
        }
        return principal.id();
    }

    public static Long currentAdminId() {
        AuthenticatedPrincipal principal = current();
        if (principal.scope() != AuthScope.ADMIN) {
            throw new IllegalArgumentException("관리자 권한이 필요합니다.");
        }
        return principal.id();
    }
}
