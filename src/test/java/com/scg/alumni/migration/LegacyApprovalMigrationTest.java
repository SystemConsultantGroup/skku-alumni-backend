package com.scg.alumni.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class LegacyApprovalMigrationTest {
    @Test void activeHistoriesArePreservedPerHistoryWithoutApprovingPending() throws Exception {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("34").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("update users set status = 'ACTIVE' where id = 1");
            sql.executeUpdate("update users set status = 'PENDING' where id = 2");
            sql.executeUpdate("insert into officer_history_approvals (officer_history_id) select min(id) from officer_histories where user_id = 1 and deleted_at is null");
            sql.executeUpdate("delete from officer_history_approvals where officer_history_id in (select id from officer_histories where user_id = 2)");
            Flyway migration = Flyway.configure().dataSource(url, "sa", "").load();
            migration.migrate();
            try (var rows = sql.executeQuery("select count(*) from officer_histories h left join officer_history_approvals a on a.officer_history_id = h.id where h.user_id = 1 and h.deleted_at is null and a.officer_history_id is null")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = sql.executeQuery("select count(*) from officer_history_approvals a join officer_histories h on h.id = a.officer_history_id where h.user_id = 2")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            assertThat(migration.migrate().migrationsExecuted).isZero();
        }
    }
    @Test void rejectedRestrictionBecomesExplicitWithoutUnblockingOtherAccounts() throws Exception {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("35").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.executeUpdate("update users set status = 'REJECTED', account_blocked = false where id = 1");
            sql.executeUpdate("update users set status = 'PENDING', account_blocked = true where id = 2");
            Flyway.configure().dataSource(url, "sa", "").load().migrate();
            try (var rows = sql.executeQuery("select account_blocked from users where id in (1, 2) order by id")) {
                rows.next(); assertThat(rows.getBoolean(1)).isTrue();
                rows.next(); assertThat(rows.getBoolean(1)).isTrue();
            }
        }
    }
}
