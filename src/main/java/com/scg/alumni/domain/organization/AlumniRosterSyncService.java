package com.scg.alumni.domain.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
public class AlumniRosterSyncService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final AlumniOrganizationService organizations;
    private final AdministrativePersonService people;
    private final ObjectMapper objectMapper;
    private final com.scg.alumni.domain.access.AccessCacheInvalidation invalidation;

    @Transactional(readOnly = true)
    public Preview preview(long organizationId, byte[] file) {
        requireActive(organizationId);
        List<AlumniRosterWorkbook.Entry> entries = AlumniRosterWorkbook.parse(file);
        List<Current> current = current(organizationId);
        String rosterHash = rosterHash(current);
        String fileHash = hash(file);
        boolean replay = isReplay(organizationId, fileHash, rosterHash);
        if (replay) return new Preview(previewToken(fileHash, rosterHash, List.of()), true, List.of(), List.of());
        List<RowPreview> rows = new ArrayList<>();
        Set<Key> retained = new HashSet<>();
        for (var entry : entries) {
            List<Map<String, Object>> candidates = List.of();
            boolean existingIdentifierConflict = false;
            String operation;
            if (entry.userId() == null) {
                candidates = people.candidates(entry.name(), entry.studentId(), entry.phone());
                existingIdentifierConflict = candidates.stream().anyMatch(candidate -> matchesIdentifier(entry, candidate));
                operation = "CREATE_USER_AND_APPOINT";
            } else {
                verifyExisting(entry);
                Key key = new Key(entry.userId(), entry.title(), entry.president());
                retained.add(key);
                Current existing = current.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
                validateDate(entry, existing);
                operation = existing == null ? "APPOINT"
                        : entry.endedAt() != null && existing.endedAt() != null && entry.endedAt().isAfter(existing.endedAt())
                        && (entry.startedAt() == null || entry.startedAt().equals(existing.startedAt())) ? "EXTEND"
                        : (entry.startedAt() != null && !entry.startedAt().equals(existing.startedAt()))
                          || (entry.endedAt() != null && !entry.endedAt().equals(existing.endedAt())) ? "UPDATE_DATES" : "KEEP";
            }
            if (entry.userId() == null) validateDate(entry, null);
            rows.add(new RowPreview(entry.row(), entry.userId(), entry.name(), entry.title(), entry.president(), operation,
                    existingIdentifierConflict, candidates.size() >= 101, candidates));
        }
        List<Current> ending = current.stream().filter(item -> !retained.contains(item.key())).toList();
        String token = previewToken(fileHash, rosterHash, rows);
        return new Preview(token, replay, rows, ending);
    }

    @Transactional
    public Result apply(long organizationId, byte[] file, String token, boolean confirmed) {
        if (!confirmed) throw conflict("명단 교체 확인이 필요합니다.");
        // Consistent lock order with single-person enrollment: identity lock, then organization lock.
        people.lockForCreation();
        organizations.lockActiveOrganization(organizationId);
        List<AlumniRosterWorkbook.Entry> entries = AlumniRosterWorkbook.parse(file);
        List<Current> before = current(organizationId);
        String fileHash = hash(file);
        Preview checked = preview(organizationId, file);
        if (!checked.token().equals(token)) throw conflict("명단 또는 후보가 변경되었습니다. 다시 미리보기 해주세요.");
        if (checked.alreadyApplied()) return new Result(true, 0, 0, List.of());
        if (checked.rows().stream().anyMatch(RowPreview::existingIdentifierConflict)) {
            throw conflict("학번 또는 전화번호가 일치하는 기존 회원을 명시적으로 선택하세요.");
        }
        if (checked.rows().stream().anyMatch(RowPreview::candidatesTruncated)) {
            throw conflict("후보가 너무 많은 행의 기존 회원ID를 명시해 주세요.");
        }
        Map<Key, Current> currentByKey = new HashMap<>();
        for (Current item : before) {
            if (currentByKey.put(item.key(), item) != null) throw conflict("기존 명단에 중복된 직책이 있습니다. 먼저 확인해주세요.");
        }
        List<Resolved> resolved = new ArrayList<>();
        List<CreatedUser> created = new ArrayList<>();
        Set<Key> wanted = new HashSet<>();
        for (var entry : entries) {
            long userId = entry.userId() == null
                    ? people.create(entry.name(), entry.studentId(), entry.phone(), true) : entry.userId();
            if (entry.userId() == null) created.add(new CreatedUser(userId, entry.name(), people.loginId(userId)));
            Key key = new Key(userId, entry.title(), entry.president());
            if (!wanted.add(key)) throw conflict("같은 회원의 동일 직책이 중복됩니다.");
            resolved.add(new Resolved(userId, entry));
        }
        int ended = 0;
        for (Current item : before) {
            if (!wanted.contains(item.key())) {
                organizations.expire(organizationId, item.id());
                ended++;
            }
        }
        int added = 0;
        for (Resolved item : resolved) {
            var entry = item.entry();
            Key key = new Key(item.userId(), entry.title(), entry.president());
            if (!currentByKey.containsKey(key)) {
                organizations.addOfficer(organizationId, item.userId(), entry.title(), entry.president(), entry.startedAt(), entry.endedAt());
                added++;
            } else if (entry.startedAt() != null || entry.endedAt() != null) {
                Current existing = currentByKey.get(key);
                jdbc.update("""
                        update alumni_organization_officers
                        set known_started_at = coalesce(?, known_started_at), effective_started_at = coalesce(?, effective_started_at),
                            ended_at = coalesce(?, ended_at), scheduled_ended_at = coalesce(?, scheduled_ended_at),
                            updated_at = CURRENT_TIMESTAMP where id = ?
                        """, entry.startedAt(), entry.startedAt(), entry.endedAt(), entry.endedAt(), existing.id());
                if (existing.president()) invalidation.afterCommit(existing.userId());
            }
        }
        jdbc.update("insert into alumni_roster_syncs (organization_id, file_hash, result_hash) values (?, ?, ?)",
                organizationId, fileHash, rosterHash(current(organizationId)));
        return new Result(false, ended, added, List.copyOf(created));
    }

    private void requireActive(long organizationId) {
        Integer count = jdbc.queryForObject("select count(*) from alumni_organizations where id = ? and dissolved_at is null", Integer.class, organizationId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "단위동문회를 찾을 수 없습니다.");
    }

    private void validateDate(AlumniRosterWorkbook.Entry entry, Current existing) {
        LocalDate today = LocalDate.now(SEOUL);
        LocalDate start = entry.startedAt() != null ? entry.startedAt() : existing == null ? null : existing.startedAt();
        LocalDate end = entry.endedAt() != null ? entry.endedAt() : existing == null ? null : existing.endedAt();
        if ((start != null && start.isAfter(today)) || (start != null && end != null && end.isBefore(start))
                || (start == null && end != null && end.isBefore(today))) {
            throw conflict(entry.row() + "행: 임기 날짜를 확인하세요.");
        }
    }

    private void verifyExisting(AlumniRosterWorkbook.Entry entry) {
        List<Identity> matches = jdbc.query("select name, student_id, phone from users where id = ? and deleted_at is null",
                (rs, row) -> new Identity(rs.getString(1), rs.getString(2), rs.getString(3)), entry.userId());
        if (matches.size() != 1 || !entry.name().equals(matches.get(0).name())
                || (entry.studentId() != null && !entry.studentId().equals(matches.get(0).studentId()))
                || (entry.phone() != null && (matches.get(0).phone() == null
                    || !digits(entry.phone()).equals(digits(matches.get(0).phone()))))) {
            throw conflict(entry.row() + "행: 회원ID와 이름을 확인하세요.");
        }
    }

    private String digits(String phone) { return phone.replaceAll("[^0-9]", ""); }
    private boolean matchesIdentifier(AlumniRosterWorkbook.Entry entry, Map<String, Object> candidate) {
        Object student = candidate.get("student_id");
        Object phone = candidate.get("phone");
        return (entry.studentId() != null && entry.studentId().equals(student))
                || (entry.phone() != null && phone != null && digits(entry.phone()).equals(digits(phone.toString())));
    }

    private List<Current> current(long organizationId) {
        LocalDate today = LocalDate.now(SEOUL);
        return jdbc.query("""
                select id, user_id, title, president, effective_started_at, ended_at
                from alumni_organization_officers where organization_id = ? and ended_at_recorded_at is null
                  and (ended_at is null or ended_at >= ?) order by id
                """, (rs, row) -> new Current(rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                rs.getBoolean("president"), rs.getObject("effective_started_at", LocalDate.class),
                rs.getObject("ended_at", LocalDate.class)), organizationId, today);
    }

    private String rosterHash(List<Current> items) {
        return hashJson(items.stream().map(item -> new HashableCurrent(
                item.id(), item.userId(), item.title(), item.president(),
                item.startedAt() == null ? null : item.startedAt().toString(),
                item.endedAt() == null ? null : item.endedAt().toString())).toList());
    }
    private String previewToken(String fileHash, String rosterHash, List<RowPreview> rows) {
        return hashJson(List.of(fileHash, rosterHash, rows));
    }
    private String hashJson(Object value) {
        try { return hash(objectMapper.writeValueAsBytes(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private boolean isReplay(long organizationId, String fileHash, String rosterHash) {
        Integer count = jdbc.queryForObject("""
                select count(*) from alumni_roster_syncs where organization_id = ? and file_hash = ? and result_hash = ?
                """, Integer.class, organizationId, fileHash, rosterHash);
        return count != null && count > 0;
    }
    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    public record Key(long userId, String title, boolean president) { }
    public record Current(long id, long userId, String title, boolean president, LocalDate startedAt, LocalDate endedAt) {
        Key key() { return new Key(userId, title, president); }
    }
    private record HashableCurrent(long id, long userId, String title, boolean president, String startedAt, String endedAt) { }
    private record Resolved(long userId, AlumniRosterWorkbook.Entry entry) { }
    private record Identity(String name, String studentId, String phone) { }
    public record RowPreview(int row, Long userId, String name, String title, boolean president,
                             String operation, boolean existingIdentifierConflict, boolean candidatesTruncated,
                             List<Map<String, Object>> candidates) { }
    public record Preview(String token, boolean alreadyApplied, List<RowPreview> rows, List<Current> ending) { }
    public record CreatedUser(long userId, String name, String initialLoginId) { }
    public record Result(boolean unchanged, int ended, int added, List<CreatedUser> createdUsers) { }
}
