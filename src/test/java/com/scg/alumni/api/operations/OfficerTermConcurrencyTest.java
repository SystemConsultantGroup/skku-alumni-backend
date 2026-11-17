package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.scg.alumni.domain.officer.OfficerTermPolicyProperties;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class OfficerTermConcurrencyTest {
    @Test void simultaneousActivationsLeaveOnlyOneCurrentTerm() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:term_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table officer_rollover_lock (id int primary key)");
        jdbc.execute("insert into officer_rollover_lock values (1)");
        jdbc.execute("create table officer_terms (id bigint primary key, generation int, phase int, started_at date, ended_at date, current_term boolean, updated_at timestamp)");
        var today = java.time.LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("insert into officer_terms values (1, 1, 1, ?, ?, true, CURRENT_TIMESTAMP), (2, 2, 1, ?, ?, false, CURRENT_TIMESTAMP)",
                today.minusYears(1), today.minusDays(1), today, today.plusYears(1));
        var service = new OfficerTermManagementService(jdbc, new OfficerTermPolicyProperties(7, ZoneId.of("Asia/Seoul")), mock(AdminAuditLog.class));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> activate(tx, service, gate, 2));
            var second = pool.submit(() -> activate(tx, service, gate, 2));
            gate.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) || second.get(15, TimeUnit.SECONDS)).isTrue();
            // Wait for both workers even when the first succeeded.
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from officer_terms where current_term = true", Integer.class)).isEqualTo(1);
    }

    private boolean activate(TransactionTemplate tx, OfficerTermManagementService service, CountDownLatch gate, long id) throws Exception {
        gate.await();
        try {
            tx.executeWithoutResult(status -> service.activate(id));
            return true;
        } catch (ResponseStatusException conflict) {
            return false;
        }
    }
}
