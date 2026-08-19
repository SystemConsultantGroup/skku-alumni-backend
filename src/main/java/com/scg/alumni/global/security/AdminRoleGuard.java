package com.scg.alumni.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자 역할(MASTER / STAFF / ASIS_OPERATOR)을 확인한다.
 *
 * <p>지금까지 보안 설정은 "관리자인가"까지만 봤다. 사무총장·직원·근로장학생을
 * 나눠 놓고도 실제로는 누구나 모든 관리 기능을 쓸 수 있었다.
 *
 * <p>역할을 토큰에 넣지 않고 매 요청 DB에서 읽는다. 권한을 회수한 즉시
 * 반영되어야 하는데, 토큰에 박아두면 만료까지 남아 있기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class AdminRoleGuard {

    public static final String MASTER = "MASTER";
    public static final String STAFF = "STAFF";
    public static final String ASIS_OPERATOR = "ASIS_OPERATOR";

    private final JdbcTemplate jdbcTemplate;

    public String currentRole() {
        Long adminId = AuthContext.currentAdminId();
        return jdbcTemplate.query("""
                select ar.name
                from admins a
                join admin_roles ar on ar.id = a.admin_role_id
                where a.id = ? and a.active = true
                """, (resultSet, rowNum) -> resultSet.getString(1), adminId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "사용할 수 없는 관리자 계정입니다."));
    }

    /** 운영자 계정을 다루는 일은 사무총장만 할 수 있다. */
    public void requireMaster() {
        if (!MASTER.equals(currentRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "사무총장 계정만 사용할 수 있는 기능입니다.");
        }
    }
}
