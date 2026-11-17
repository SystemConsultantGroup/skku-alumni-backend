package com.scg.alumni.domain.organization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scg.alumni.api.operations.AdminAuditLog;
import com.scg.alumni.domain.access.AccessCacheInvalidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CombinedRosterService {
    private final JdbcTemplate jdbc;
    private final AlumniRosterSyncService roster;
    private final AdministrativePersonService people;
    private final AlumniOrganizationService organizations;
    private final AdminAuditLog audit;
    private final AccessCacheInvalidation invalidation;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public Preview preview(byte[] file) {
        List<Group> groups = groups(file);
        return previewGroups(file, groups);
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> apply(byte[] file, String token, boolean confirmed) {
        if (!confirmed) throw conflict("명단 교체 확인이 필요합니다.");
        people.lockForCreation();
        List<Group> groups = groups(file);
        for (Group group : groups) organizations.lockActiveOrganization(group.id());
        Preview checked = previewGroups(file, groups);
        if (!checked.token().equals(token)) throw conflict("미리보기 이후 명단 또는 회원 정보가 변경되었습니다.");
        long auditId = audit.recordAndGetId("SYNC_COMBINED_ALUMNI_ROSTER", "alumni_roster_upload", null);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Group group : groups) {
            List<RowSnapshot> before = snapshot(group.id());
            var singlePreview = roster.preview(group.id(), group.file());
            var result = roster.apply(group.id(), group.file(), singlePreview.token(), true);
            if (currentCount(group.id()) == 0) throw conflict("업로드로 현직 인원이 0명이 될 수 없습니다: " + group.name());
            List<RowSnapshot> after = snapshot(group.id());
            List<RowSnapshot> changedBefore = new ArrayList<>(), changedAfter = new ArrayList<>();
            Map<Long, RowSnapshot> original = byId(before), updated = byId(after);
            for (RowSnapshot row : before) if (!row.equals(updated.get(row.id()))) changedBefore.add(row);
            for (RowSnapshot row : after) if (!row.equals(original.get(row.id()))) changedAfter.add(row);
            jdbc.update("""
                    insert into alumni_roster_snapshots (audit_log_id, organization_id, before_snapshot, after_snapshot, after_hash, added_count, ended_count)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, auditId, group.id(), json(changedBefore), json(changedAfter), stateHash(group.id(), after), result.added(), result.ended());
            results.add(Map.of("organizationId", group.id(), "name", group.name(), "added", result.added(), "ended", result.ended(), "unchanged", result.unchanged(), "createdUsers", result.createdUsers()));
        }
        return Map.of("jobId", auditId, "organizations", results);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> jobs(Long cursor, int size) {
        return jdbc.queryForList("""
                select a.id, a.admin_id, a.created_at,
                       (select count(*) from alumni_roster_snapshots s where s.audit_log_id = a.id) as organization_count,
                       (select count(*) from alumni_roster_snapshots s where s.audit_log_id = a.id and s.rolled_back_at is not null) as rolled_back_count
                from admin_audit_logs a
                where a.action = 'SYNC_COMBINED_ALUMNI_ROSTER' and (? is null or a.id < ?)
                order by a.id desc limit ?
                """, cursor, cursor, Math.max(1, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> details(long jobId) {
        List<Map<String, Object>> details = jdbc.queryForList("""
                select s.organization_id, o.name, s.added_count, s.ended_count, s.rolled_back_at, s.rollback_audit_log_id
                from alumni_roster_snapshots s join alumni_organizations o on o.id = s.organization_id
                where s.audit_log_id = ? order by s.organization_id
                """, jobId);
        for (Map<String, Object> item : details) {
            long id = ((Number) item.get("organization_id")).longValue();
            var snapshot = jdbc.queryForMap("select before_snapshot, after_snapshot from alumni_roster_snapshots where audit_log_id = ? and organization_id = ?", jobId, id);
            item.put("before", read((String) snapshot.get("before_snapshot")));
            item.put("after", read((String) snapshot.get("after_snapshot")));
        }
        return details;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rollbackPreview(long jobId, List<Long> ids) {
        return selected(jobId, ids).stream().map(this::rollbackStatus).toList();
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<Map<String, Object>> rollback(long jobId, List<Long> ids) {
        List<Stored> selected = selected(jobId, ids);
        for (Stored item : selected) jdbc.queryForObject("select id from alumni_organizations where id = ? for update", Long.class, item.organizationId());
        // Reload after locking: another rollback may have committed while we waited.
        selected = selected(jobId, ids);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Stored item : selected) {
            Map<String, Object> status = rollbackStatus(item);
            if (!"READY".equals(status.get("status"))) { results.add(status); continue; }
            List<RowSnapshot> before = read(item.before()), after = read(item.after());
            Map<Long, RowSnapshot> originals = byId(before);
            Set<Long> affected = new HashSet<>();
            for (RowSnapshot row : after) {
                affected.add(row.userId());
                if (!originals.containsKey(row.id())) {
                    // Preserve the newly-created appointment as an ended historical record.
                    jdbc.update("""
                            update alumni_organization_officers set ended_at = case when ended_at is null or ended_at > CURRENT_DATE then CURRENT_DATE else ended_at end,
                                   ended_at_recorded_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                            where id = ? and organization_id = ?
                            """, row.id(), item.organizationId());
                }
            }
            for (RowSnapshot row : before) {
                affected.add(row.userId());
                jdbc.update("""
                        update alumni_organization_officers set title = ?, president = ?, known_started_at = ?, effective_started_at = ?,
                          scheduled_ended_at = ?, ended_at = ?, ended_at_recorded_at = ?, updated_at = CURRENT_TIMESTAMP
                        where id = ? and organization_id = ? and user_id = ?
                        """, row.title(), row.president(), date(row.knownStart()), date(row.effectiveStart()), date(row.scheduledEnd()),
                        date(row.end()), timestamp(row.recordedEnd()), row.id(), item.organizationId(), row.userId());
            }
            organizations.touchRoster(item.organizationId());
            affected.forEach(invalidation::afterCommit);
            long rollbackAudit = audit.recordAndGetId("ROLLBACK_ALUMNI_ROSTER", "alumni_roster_upload", jobId);
            jdbc.update("update alumni_roster_snapshots set rollback_audit_log_id = ?, rolled_back_at = CURRENT_TIMESTAMP where audit_log_id = ? and organization_id = ?",
                    rollbackAudit, jobId, item.organizationId());
            results.add(Map.of("organizationId", item.organizationId(), "status", "ROLLED_BACK"));
        }
        return results;
    }

    private Preview previewGroups(byte[] file, List<Group> groups) {
        List<GroupPreview> result = new ArrayList<>();
        for (Group group : groups) {
            var preview = roster.preview(group.id(), group.file());
            long count = currentCount(group.id());
            var entries = AlumniRosterWorkbook.parse(group.file());
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
            if (entries.stream().allMatch(entry -> entry.endedAt() != null && entry.endedAt().isBefore(today)))
                throw conflict("업로드로 현직 인원이 0명이 될 수 없습니다: " + group.name());
            result.add(new GroupPreview(group.id(), group.name(), count, entries.size(), preview.rows(), preview.ending(), stateHash(group.id(), snapshot(group.id()))));
        }
        var tokenParts = result.stream().map(group -> List.of(group.organizationId(), group.name(), group.existingCount(),
                group.uploadedCount(), group.rows(), group.ending().stream().map(Object::toString).toList(), group.stateHash())).toList();
        return new Preview(hash(json(List.of(hash(file), tokenParts))), result);
    }

    private List<Group> groups(byte[] file) {
        var grouped = CombinedRosterWorkbook.split(file);
        List<Org> known = jdbc.query("select id, name from alumni_organizations where dissolved_at is null order by id",
                (rs, n) -> new Org(rs.getLong(1), rs.getString(2)));
        List<Group> result = new ArrayList<>();
        Set<String> newIdentifiers = new HashSet<>();
        for (var entry : grouped.entrySet()) {
            var matches = known.stream().filter(o -> CombinedRosterWorkbook.normalize(o.name()).equals(entry.getKey())).toList();
            if (matches.size() != 1) throw conflict("단위동문회 이름이 없거나 중복됩니다: " + entry.getKey());
            for (var person : AlumniRosterWorkbook.parse(entry.getValue())) {
                if (person.userId() == null) {
                    if (!newIdentifiers.add("n:" + person.name())) throw conflict("같은 이름의 신규 인물은 먼저 등록하고 회원ID를 입력하세요.");
                    if (person.studentId() != null && !newIdentifiers.add("s:" + person.studentId())) throw conflict("여러 조직에서 신규 인물이 중복됩니다. 먼저 회원을 등록하고 회원ID를 입력하세요.");
                    if (person.phone() != null && !newIdentifiers.add("p:" + person.phone().replaceAll("[^0-9]", ""))) throw conflict("여러 조직에서 신규 인물이 중복됩니다. 먼저 회원을 등록하고 회원ID를 입력하세요.");
                }
            }
            result.add(new Group(matches.getFirst().id(), matches.getFirst().name(), entry.getValue()));
        }
        result.sort(Comparator.comparingLong(Group::id));
        return result;
    }

    private long currentCount(long organizationId) {
        return jdbc.queryForObject("select count(*) from alumni_organization_officers where organization_id = ? and ended_at_recorded_at is null and (ended_at is null or ended_at >= CURRENT_DATE)", Long.class, organizationId);
    }
    private List<RowSnapshot> snapshot(long id) {
        return jdbc.query("""
                select id, user_id, title, president, known_started_at, effective_started_at, scheduled_ended_at, ended_at, ended_at_recorded_at, updated_at
                from alumni_organization_officers where organization_id = ? order by id
                """, (rs, n) -> new RowSnapshot(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), id);
    }
    private String stateHash(long id, List<RowSnapshot> rows) {
        var org = jdbc.queryForList("select id, name, description, dissolved_at, updated_at, roster_revision from alumni_organizations where id = ?", id);
        return hash(json(List.of(org, rows)));
    }
    private Map<String, Object> rollbackStatus(Stored item) {
        String status = item.rolledBack() ? "ALREADY_ROLLED_BACK" : item.hash().equals(stateHash(item.organizationId(), snapshot(item.organizationId()))) ? "READY" : "CONFLICT";
        return Map.of("organizationId", item.organizationId(), "status", status,
                "reason", "CONFLICT".equals(status) ? "업로드 이후 조직 또는 소속 이력이 변경되었습니다." : "");
    }
    private List<Stored> selected(long jobId, List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) throw conflict("롤백할 단위동문회를 확인하세요.");
        List<Stored> result = new ArrayList<>();
        for (Long id : ids.stream().sorted().toList()) {
            var rows = jdbc.query("select organization_id, before_snapshot, after_snapshot, after_hash, rolled_back_at from alumni_roster_snapshots where audit_log_id = ? and organization_id = ?",
                    (rs, n) -> new Stored(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5) != null), jobId, id);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "업로드 작업의 단위동문회를 찾을 수 없습니다.");
            result.add(rows.getFirst());
        }
        return result;
    }
    private Map<Long, RowSnapshot> byId(List<RowSnapshot> rows) { Map<Long, RowSnapshot> map = new LinkedHashMap<>(); rows.forEach(r -> map.put(r.id(), r)); return map; }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private List<RowSnapshot> read(String value) { try { return mapper.readValue(value, new TypeReference<List<RowSnapshot>>() {}); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    private String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private LocalDate date(String value) { return value == null ? null : LocalDate.parse(value); }
    private java.sql.Timestamp timestamp(String value) { return value == null ? null : java.sql.Timestamp.valueOf(value); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Preview(String token, List<GroupPreview> organizations) { }
    public record GroupPreview(long organizationId, String name, long existingCount, int uploadedCount,
                               List<AlumniRosterSyncService.RowPreview> rows, List<AlumniRosterSyncService.Current> ending, String stateHash) { }
    private record Group(long id, String name, byte[] file) { }
    private record Org(long id, String name) { }
    private record Stored(long organizationId, String before, String after, String hash, boolean rolledBack) { }
    public record RowSnapshot(long id, long userId, String title, boolean president, String knownStart, String effectiveStart,
                              String scheduledEnd, String end, String recordedEnd, String updatedAt) { }
}
