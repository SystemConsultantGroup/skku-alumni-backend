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
            assertThat(statement.executeUpdate("update payment_records set amount = 123456, status = 'PAID' where user_id = 1 and officer_term_id = 3")).isEqualTo(1);
        }
        Flyway next = Flyway.configure().dataSource(url, user, password).load();
        next.migrate();
        next.validate();
        assertThat(next.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement();
             var results = statement.executeQuery("select amount, status, deleted_at from payment_records where user_id = 1 and officer_term_id = 3")) {
            assertThat(results.next()).isTrue();
            assertThat(results.getInt("amount")).isEqualTo(123456);
            assertThat(results.getString("status")).isEqualTo("PAID");
            assertThat(results.getTimestamp("deleted_at")).isNull();
        }
    }
}
