package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.scg.alumni.support.RedislessTestConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(RedislessTestConfig.class)
@Transactional
class AdminOfficerDirectoryControllerTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminOfficerDirectoryController controller;

    @Test
    void loadsCentralAppointmentsAndFindsUnitAppointmentsByTitle() {
        jdbc.update("insert into alumni_organizations (name) values ('가상 지역동문회')");
        Long organizationId = jdbc.queryForObject(
                "select id from alumni_organizations where name = '가상 지역동문회'", Long.class);
        jdbc.update("""
                insert into alumni_organization_officers (organization_id, user_id, title, effective_started_at)
                values (?, 1, '가상 동문회 간사', ?)
                """, organizationId, LocalDate.of(2026, 5, 1));

        var firstPage = controller.list(null, null, 0, 1);
        assertThat(firstPage.get("hasNext")).isEqualTo(true);
        assertThat(rows(firstPage)).singleElement().satisfies(row ->
                assertThat(row.get("kind")).isEqualTo("CENTRAL"));

        var unit = controller.list("동문회 간사", "UNIT", 0, 20);
        assertThat(rows(unit)).singleElement().satisfies(row -> {
            assertThat(row.get("organizationName")).isEqualTo("가상 지역동문회");
            assertThat(row.get("title")).isEqualTo("가상 동문회 간사");
        });
        assertThat(rows(controller.list("동문회 간사", "CENTRAL", 0, 20))).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("items");
    }
}
