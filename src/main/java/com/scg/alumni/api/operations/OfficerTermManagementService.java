package com.scg.alumni.api.operations;

import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OfficerTermManagementService {
    private final JdbcTemplate jdbc;
    private final OfficerTermPolicyProperties policy;
    private final AdminAuditLog audit;

    @Transactional
    public Map<String, Object> create(int generation, int phase, LocalDate start, LocalDate end) {
        if (generation <= 0 || phase < 1 || phase > 2 || start == null || end == null
                || start.getMonthValue() != 5 || start.getDayOfMonth() != 1 || !end.equals(start.plusYears(1).minusDays(1)))
            throw error(HttpStatus.BAD_REQUEST, "임기는 5월 1일부터 다음 해 4월 30일까지이며 기수는 1 또는 2여야 합니다.");
        jdbc.queryForObject("select id from officer_rollover_lock where id = 1 for update", Integer.class);
        if (jdbc.queryForObject("select count(*) from officer_terms where generation = ? and phase = ?", Integer.class, generation, phase) > 0)
            throw error(HttpStatus.CONFLICT, "이미 등록된 대·기입니다.");
        if (jdbc.queryForObject("select count(*) from officer_terms where started_at <= ? and ended_at >= ?", Integer.class, end, start) > 0)
            throw error(HttpStatus.CONFLICT, "기존 임기와 기간이 겹칩니다.");
        var previous = jdbc.query("select generation, phase from officer_terms where ended_at = ?",
                (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, start.minusDays(1));
        if (!previous.isEmpty()) {
            int[] prior = previous.getFirst();
            int expectedGeneration = prior[1] == 2 ? prior[0] + 1 : prior[0];
            int expectedPhase = prior[1] == 2 ? 1 : 2;
            if (generation != expectedGeneration || phase != expectedPhase)
                throw error(HttpStatus.CONFLICT, "이전 임기에 이어지는 대·기가 아닙니다.");
        }
        long id = new SimpleJdbcInsert(jdbc).withTableName("officer_terms")
                .usingColumns("generation", "phase", "started_at", "ended_at", "current_term")
                .usingGeneratedKeyColumns("id").executeAndReturnKey(Map.of("generation", generation, "phase", phase,
                        "started_at", start, "ended_at", end, "current_term", false)).longValue();
        audit.record("CREATE_OFFICER_TERM", "officer_term", id);
        return Map.of("id", id, "generation", generation, "phase", phase, "startedAt", start, "endedAt", end, "currentTerm", !start.isAfter(LocalDate.now(policy.zone())) && !end.isBefore(LocalDate.now(policy.zone())));
    }

    public List<TermOption> options() { return options(LocalDate.now(policy.zone())); }

    List<TermOption> options(LocalDate today) {
        List<TermOption> result = new ArrayList<>();
        var current = findByDate(today, "CURRENT");
        if (current.size() > 1) throw error(HttpStatus.CONFLICT, "현재 날짜에 해당하는 임기가 여러 개입니다.");
        result.addAll(current);
        if (!current.isEmpty() && !today.isBefore(LocalDate.of(today.getYear(), 4, 1)) && today.getMonthValue() == 4) {
            LocalDate nextStart = LocalDate.of(today.getYear(), 5, 1);
            result.addAll(jdbc.query("select id, generation, phase, started_at, ended_at from officer_terms where started_at = ? and ended_at = ?",
                    (rs, n) -> new TermOption(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, LocalDate.class),
                            rs.getObject(5, LocalDate.class), "NEXT", true), nextStart, nextStart.plusYears(1).minusDays(1)));
        }
        return result;
    }

    private List<TermOption> findByDate(LocalDate date, String type) {
        return jdbc.query("select id, generation, phase, started_at, ended_at from officer_terms where started_at <= ? and ended_at >= ? order by started_at desc",
                (rs, n) -> new TermOption(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), type, true), date, date);
    }

    public long resolveSelection(Long id) {
        return resolveSelection(id, LocalDate.now(policy.zone()));
    }

    long resolveSelection(Long id, LocalDate date) {
        List<TermOption> options = options(date);
        return options.stream().filter(option -> id == null ? "CURRENT".equals(option.type()) : option.officerTermId() == id)
                .findFirst().orElseThrow(() -> error(HttpStatus.CONFLICT, "선택 가능한 현 임기 또는 차기 임기가 아닙니다.")).officerTermId();
    }

    /** Compatibility endpoint: dates, not this call or the legacy flag, decide the current term. */
    public Map<String, Object> activate(long id) {
        if (options().stream().noneMatch(term -> "CURRENT".equals(term.type()) && term.officerTermId() == id))
            throw error(HttpStatus.CONFLICT, "현재 날짜에 해당하는 임기만 활성화할 수 있습니다.");
        return Map.of("id", id, "currentTerm", true);
    }

    private ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    public record TermOption(long officerTermId, int generation, int phase, LocalDate startedAt, LocalDate endedAt,
                             String type, boolean selectable) { }
}
