package com.scg.alumni.domain.organization;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class VipQualificationService {
    private final JdbcTemplate jdbc;
    private final com.scg.alumni.domain.access.AccessCacheInvalidation invalidation;

    @Transactional
    public void grant(long userId) {
        lockUser(userId);
        Integer count = jdbc.queryForObject(
                "select count(*) from vip_qualifications where user_id = ? and revoked_at is null", Integer.class, userId);
        if (count != null && count > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "VIP qualification already exists");
        jdbc.update("insert into vip_qualifications (user_id) values (?)", userId);
        invalidation.afterCommit(userId);
    }

    @Transactional
    public void revoke(long userId) {
        lockUser(userId);
        int updated = jdbc.update("update vip_qualifications set revoked_at = CURRENT_TIMESTAMP where user_id = ? and revoked_at is null", userId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active VIP qualification not found");
        invalidation.afterCommit(userId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long userId, Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        return jdbc.queryForList("""
                select id, user_id, granted_at, revoked_at from vip_qualifications
                where user_id = ? and (? is null or id < ?) order by id desc limit ?
                """, userId, cursor, cursor, limit + 1);
    }

    private void lockUser(long userId) {
        List<Long> rows = jdbc.query("select id from users where id = ? and deleted_at is null for update",
                (rs, row) -> rs.getLong(1), userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
    }
}
