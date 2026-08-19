package com.scg.alumni.domain.officer;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임기(대·기)를 달력에 맞춰 넘겨준다.
 *
 * <p>임기는 5월 1일에 시작해 다음 해 4월 30일에 끝나고, 한 대(代)는 1기·2기
 * 두 해로 이뤄진다. 지금까지 {@code officer_terms.current_term}은 시드로 박힌
 * 플래그여서 5월 1일이 지나도 아무도 넘겨주지 않았다. 회의에서 설명한
 * "시스템에 클럭이 있어서 2년에 한 칸씩 간다"는 동작을 여기서 담당한다.
 *
 * <p>서버가 전환 시점에 꺼져 있었을 수 있으므로 기동 직후에도 한 번 맞춘다.
 * 여러 해가 밀려 있어도 오늘 날짜에 닿을 때까지 임기를 이어서 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficerTermRolloverService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 한 대(代)를 이루는 기(期)의 수. 1기와 2기가 끝나면 다음 대로 넘어간다. */
    private static final int PHASES_PER_GENERATION = 2;

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void rolloverOnStartup() {
        rollover();
    }

    /** 매일 새벽에 확인한다. 전환일(5월 1일) 당일 첫 실행에서 넘어간다. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    public void rolloverDaily() {
        rollover();
    }

    @Transactional
    public void rollover() {
        LocalDate today = LocalDate.now(KST);
        Long termId = findTermIdCovering(today);
        while (termId == null) {
            if (!appendNextTerm()) {
                log.warn("임기를 만들 기준이 없어 전환을 건너뜁니다. officer_terms가 비어 있습니다.");
                return;
            }
            termId = findTermIdCovering(today);
        }
        markCurrent(termId);
    }

    private Long findTermIdCovering(LocalDate day) {
        return jdbcTemplate.query("""
                select id
                from officer_terms
                where started_at <= ? and ended_at >= ?
                order by started_at desc
                limit 1
                """, (resultSet, rowNum) -> resultSet.getLong(1), day, day)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** 마지막 임기 다음 한 칸을 만든다. 만들 기준이 없으면 false. */
    private boolean appendNextTerm() {
        Map<String, Object> latest = jdbcTemplate.query("""
                select generation, phase, ended_at
                from officer_terms
                order by started_at desc
                limit 1
                """, (resultSet, rowNum) -> Map.<String, Object>of(
                        "generation", resultSet.getInt("generation"),
                        "phase", resultSet.getInt("phase"),
                        "endedAt", resultSet.getObject("ended_at", LocalDate.class)))
                .stream()
                .findFirst()
                .orElse(null);
        if (latest == null) {
            return false;
        }

        int generation = (int) latest.get("generation");
        int phase = (int) latest.get("phase");
        int nextGeneration = phase >= PHASES_PER_GENERATION ? generation + 1 : generation;
        int nextPhase = phase >= PHASES_PER_GENERATION ? 1 : phase + 1;

        LocalDate startedAt = ((LocalDate) latest.get("endedAt")).plusDays(1);
        LocalDate endedAt = startedAt.plusYears(1).minusDays(1);

        jdbcTemplate.update("""
                insert into officer_terms (generation, phase, started_at, ended_at, current_term, created_at, updated_at)
                values (?, ?, ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, nextGeneration, nextPhase, startedAt, endedAt);
        log.info("새 임기를 만들었습니다. {}대 {}기 ({} ~ {})", nextGeneration, nextPhase, startedAt, endedAt);
        return true;
    }

    private void markCurrent(Long termId) {
        Integer alreadyCurrent = jdbcTemplate.queryForObject("""
                select count(*) from officer_terms where id = ? and current_term = true
                """, Integer.class, termId);
        if (alreadyCurrent != null && alreadyCurrent > 0) {
            return;
        }
        jdbcTemplate.update("update officer_terms set current_term = false, updated_at = CURRENT_TIMESTAMP where current_term = true");
        jdbcTemplate.update("update officer_terms set current_term = true, updated_at = CURRENT_TIMESTAMP where id = ?", termId);
        Map<String, Object> term = jdbcTemplate.queryForMap("select generation, phase from officer_terms where id = ?", termId);
        log.info("현행 임기를 {}대 {}기로 전환했습니다.", term.get("generation"), term.get("phase"));
    }
}
