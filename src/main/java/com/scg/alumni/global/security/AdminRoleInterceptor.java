package com.scg.alumni.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 역할에 따라 접근할 수 있는 관리 기능을 제한한다.
 *
 * <p>사무총장 / 사무처 직원 / 근로장학생 세 역할을 나눠 놓고도 지금까지는
 * "관리자인가"만 확인해서 근로장학생 계정으로도 회비와 신고를 건드릴 수 있었다.
 * 회의에서 설명한 "근로장학생으로 들어가면 ASIS 최신화 말고는 아무것도 못한다"를
 * 실제로 강제한다.
 *
 * <p>화면을 숨기는 것만으로는 부족하다. 관리 API는 주소만 알면 바로 부를 수 있다.
 */
@Component
@RequiredArgsConstructor
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final String ASIS_SYNC_PREFIX = "/api/v1/admin/asis-sync";
    private static final String MANAGERS_PREFIX = "/api/v1/admin/managers";
    private static final String MANAGER_ROLES_PATH = "/api/v1/admin/manager-roles";

    private final AdminRoleGuard adminRoleGuard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String role = adminRoleGuard.currentRole();

        if (AdminRoleGuard.MASTER.equals(role)) {
            return true;
        }
        if (AdminRoleGuard.ASIS_OPERATOR.equals(role)) {
            if (path.startsWith(ASIS_SYNC_PREFIX)) {
                return true;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ASIS 최신화만 처리할 수 있는 계정입니다.");
        }
        // 사무처 직원. 운영자 계정 관리만 사무총장 몫으로 남긴다.
        if (path.startsWith(MANAGERS_PREFIX) || path.equals(MANAGER_ROLES_PATH)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "사무총장 계정만 사용할 수 있는 기능입니다.");
        }
        return true;
    }
}
