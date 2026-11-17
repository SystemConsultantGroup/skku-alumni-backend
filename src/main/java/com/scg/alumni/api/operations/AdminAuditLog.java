package com.scg.alumni.api.operations;

import com.scg.alumni.global.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자가 무엇을 건드렸는지 남긴다.
 *
 * <p>회원 상세 화면에서 사무처가 남의 데이터를 직접 고치고 지운다. 누가 언제
 * 무엇을 했는지 남지 않으면 나중에 "내가 안 그랬다"를 확인할 방법이 없다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLog {

    private final JdbcTemplate jdbcTemplate;

    public void record(String action, String targetType, Long targetId) {
        recordAndGetId(action, targetType, targetId);
    }

    public long recordAndGetId(String action, String targetType, Long targetId) {
        var values = new java.util.HashMap<String, Object>();
        values.put("admin_id", AuthContext.currentAdminId());
        values.put("action", action);
        values.put("target_type", targetType);
        values.put("target_id", targetId);
        return new org.springframework.jdbc.core.simple.SimpleJdbcInsert(jdbcTemplate)
                .withTableName("admin_audit_logs").usingColumns("admin_id", "action", "target_type", "target_id")
                .usingGeneratedKeyColumns("id").executeAndReturnKey(values).longValue();
    }
}
