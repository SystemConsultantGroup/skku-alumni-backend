package com.scg.alumni.domain.officer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class OfficerTermRolloverServiceTest {
    @Autowired OfficerTermRolloverService rollover;
    @Autowired JdbcTemplate jdbc;

    @Test
    void missingHistoricalTermDoesNotGenerateUnboundedFutureTerms() {
        jdbc.update("update officer_terms set started_at = '2099-05-01', ended_at = '2100-04-30'");
        Integer before = jdbc.queryForObject("select count(*) from officer_terms", Integer.class);
        rollover.rollover();
        assertThat(jdbc.queryForObject("select count(*) from officer_terms", Integer.class)).isEqualTo(before);
    }

    @Test
    void clearsExtraCurrentFlagsEvenWhenTheCorrectTermIsAlreadyCurrent() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Long currentId = jdbc.queryForObject("select id from officer_terms where started_at <= ? and ended_at >= ? order by started_at desc limit 1", Long.class, today, today);
        Long otherId = jdbc.queryForObject("select min(id) from officer_terms where id <> ?", Long.class, currentId);
        jdbc.update("update officer_terms set current_term = true where id = ? or id = ?", currentId, otherId);
        rollover.rollover();
        assertThat(jdbc.queryForObject("select count(*) from officer_terms where current_term = true", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from officer_terms where current_term = true", Long.class)).isEqualTo(currentId);
    }
}
