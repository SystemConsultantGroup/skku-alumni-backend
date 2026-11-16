package com.scg.alumni.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in: the URL is restricted to the explicitly isolated local test schema. */
@EnabledIfEnvironmentVariable(named = "SKKU_MYSQL_UPGRADE_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:33387/alumni_upgrade.*")
class MySqlUpgradeVerificationTest {
    @Test
    void upgradesV29PreservingPaidAmountAndCanRestart() throws Exception {
        String url = System.getenv("SKKU_MYSQL_UPGRADE_URL");
        String user = System.getenv("SKKU_MYSQL_UPGRADE_USER");
        String password = System.getenv("SKKU_MYSQL_UPGRADE_PASSWORD");
        Flyway old = Flyway.configure().dataSource(url, user, password).target("29").load();
        old.migrate();
        assertThat(old.info().current().getVersion().toString()).isEqualTo("29");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            assertThat(statement.executeUpdate("update payment_records set amount = 123456, status = 'PAID', paid_at = '2026-05-15 12:00:00' where user_id = 1 and officer_term_id = 3")).isEqualTo(1);
            assertThat(statement.executeUpdate("update payment_records set amount = 456789, status = 'PAID', paid_at = '2026-05-16 12:00:00', deleted_at = '2026-06-01 12:00:00' where user_id = 2 and officer_term_id = 3")).isEqualTo(1);
        }
        Flyway beforeRemoval = Flyway.configure().dataSource(url, user, password).target("32").load();
        beforeRemoval.migrate();
        Flyway next = Flyway.configure().dataSource(url, user, password).load();
        next.migrate();
        next.validate();
        assertThat(next.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement();
             var results = statement.executeQuery("select amount, status, paid_at, deleted_at from payment_records where user_id = 1 and officer_term_id = 3")) {
            assertThat(results.next()).isTrue();
            assertThat(results.getInt("amount")).isEqualTo(123456);
            assertThat(results.getString("status")).isEqualTo("PAID");
            assertThat(results.getTimestamp("paid_at").toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 15, 12, 0));
            assertThat(results.getTimestamp("deleted_at")).isNull();
        }
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("select amount, status, paid_at, deleted_at from payment_records where user_id = 2 and officer_term_id = 3")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("amount")).isEqualTo(456789);
                assertThat(rows.getString("status")).isEqualTo("PAID");
                assertThat(rows.getTimestamp("paid_at").toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 5, 16, 12, 0));
                assertThat(rows.getTimestamp("deleted_at").toLocalDateTime()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 1, 12, 0));
            }
            try (var rows = statement.executeQuery("select legacy_status from legacy_officer_payment_status_archive where officer_history_id = 2")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("PAID");
            }
            try (var rows = statement.executeQuery("select count(*) from information_schema.columns where table_schema = database() and table_name = 'officer_histories' and column_name = 'payment_status'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }
}
