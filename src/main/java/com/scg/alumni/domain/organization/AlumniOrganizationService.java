package com.scg.alumni.domain.organization;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AlumniOrganizationService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final com.scg.alumni.domain.access.AccessCacheInvalidation invalidation;

    @Transactional
    public long create(String name, String description) {
        lockOrganizationNames();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into alumni_organizations (name, description) values (?, ?)", new String[]{"id"});
            statement.setString(1, name.trim());
            statement.setString(2, description);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    @Transactional
    public void update(long organizationId, String name, String description) {
        lockOrganizationNames();
        lockActiveOrganization(organizationId);
        jdbc.update("update alumni_organizations set name = ?, description = ?, roster_revision = roster_revision + 1, updated_at = CURRENT_TIMESTAMP where id = ?",
                name.trim(), description, organizationId);
    }

    /** All mutations lock the organization row, serializing even concurrent president registrations. */
    @Transactional
    public long addOfficer(long organizationId, long userId, String title, boolean president,
            LocalDate knownStart, LocalDate end) {
        lockActiveOrganization(organizationId);
        LocalDate today = LocalDate.now(SEOUL);
        if (knownStart != null && knownStart.isAfter(today)) {
            throw error(HttpStatus.BAD_REQUEST, "Future appointments are not supported");
        }
        LocalDate effectiveStart = knownStart == null ? today : knownStart;
        if (end != null && (end.isBefore(effectiveStart) || (end.isBefore(today) && knownStart == null))) {
            throw error(HttpStatus.BAD_REQUEST, "Invalid appointment dates");
        }
        Integer users = jdbc.queryForObject("select count(*) from users where id = ? and deleted_at is null", Integer.class, userId);
        if (users == null || users == 0) throw error(HttpStatus.NOT_FOUND, "User not found");
        if (president && (end == null || !end.isBefore(today))) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from alumni_organization_officers
                    where organization_id = ? and president = true and ended_at_recorded_at is null
                      and (ended_at is null or ended_at >= ?)
                    """, Integer.class, organizationId, today);
            if (count != null && count > 0) throw error(HttpStatus.CONFLICT, "A current president already exists");
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into alumni_organization_officers
                    (organization_id, user_id, title, president, known_started_at, effective_started_at, scheduled_ended_at, ended_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setLong(1, organizationId);
            statement.setLong(2, userId);
            statement.setString(3, title.trim());
            statement.setBoolean(4, president);
            statement.setObject(5, knownStart);
            statement.setObject(6, effectiveStart);
            statement.setObject(7, end);
            statement.setObject(8, end);
            return statement;
        }, key);
        touchRoster(organizationId);
        if (president && (end == null || !end.isBefore(today))) invalidation.afterCommit(userId);
        return key.getKey().longValue();
    }

    @Transactional
    public void expire(long organizationId, long officerId) {
        lockActiveOrganization(organizationId);
        LocalDate today = LocalDate.now(SEOUL);
        List<Long> affected = jdbc.queryForList("""
                select user_id from alumni_organization_officers where id = ? and organization_id = ?
                  and president = true and ended_at_recorded_at is null and (ended_at is null or ended_at >= ?)
                """, Long.class, officerId, organizationId, today);
        int changed = jdbc.update("""
                update alumni_organization_officers set ended_at = ?, ended_at_recorded_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP where id = ? and organization_id = ? and ended_at_recorded_at is null
                and (ended_at is null or ended_at >= ?)
                """, today, officerId, organizationId, today);
        if (changed == 0) throw error(HttpStatus.NOT_FOUND, "Current appointment not found");
        touchRoster(organizationId);
        affected.forEach(invalidation::afterCommit);
    }

    @Transactional
    public void dissolve(long organizationId) {
        lockActiveOrganization(organizationId);
        LocalDate today = LocalDate.now(SEOUL);
        List<Long> affected = jdbc.queryForList("""
                select distinct user_id from alumni_organization_officers where organization_id = ?
                  and president = true and ended_at_recorded_at is null and (ended_at is null or ended_at >= ?)
                """, Long.class, organizationId, today);
        jdbc.update("""
                update alumni_organization_officers set ended_at = ?, ended_at_recorded_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP where organization_id = ? and ended_at_recorded_at is null
                and (ended_at is null or ended_at >= ?)
                """, today, organizationId, today);
        jdbc.update("update alumni_organizations set dissolved_at = CURRENT_TIMESTAMP, roster_revision = roster_revision + 1, updated_at = CURRENT_TIMESTAMP where id = ?", organizationId);
        affected.forEach(invalidation::afterCommit);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> organizations(Long cursor, int size) {
        int limit = Math.max(1, Math.min(size, 100));
        return jdbc.queryForList("""
                select id, name, description, dissolved_at from alumni_organizations
                where (? is null or id < ?) order by id desc limit ?
                """, cursor, cursor, limit + 1);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> officers(long organizationId, Long cursor, int size, boolean current) {
        int limit = Math.max(1, Math.min(size, 100));
        LocalDate today = LocalDate.now(SEOUL);
        return jdbc.queryForList("""
                select h.id, h.user_id, u.name, u.student_id, h.title, h.president, h.known_started_at,
                       h.effective_started_at, h.scheduled_ended_at, h.ended_at, h.ended_at_recorded_at
                from alumni_organization_officers h join users u on u.id = h.user_id
                where h.organization_id = ? and (? is null or h.id < ?)
                and ((? = true and h.ended_at_recorded_at is null and (h.ended_at is null or h.ended_at >= ?))
                     or (? = false and (h.ended_at_recorded_at is not null or h.ended_at < ?)))
                order by h.id desc limit ?
                """, organizationId, cursor, cursor, current, today, current, today, limit + 1);
    }

    @Transactional
    public void lockActiveOrganization(long id) {
        List<Long> rows = jdbc.query("select id from alumni_organizations where id = ? and dissolved_at is null for update",
                (rs, row) -> rs.getLong(1), id);
        if (rows.isEmpty()) throw error(HttpStatus.NOT_FOUND, "Active organization not found");
    }

    @Transactional
    public void touchRoster(long id) {
        jdbc.update("update alumni_organizations set roster_revision = roster_revision + 1, updated_at = CURRENT_TIMESTAMP where id = ?", id);
    }

    private void lockOrganizationNames() {
        jdbc.queryForObject("select id from administrative_person_lock where id = 1 for update", Integer.class);
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
