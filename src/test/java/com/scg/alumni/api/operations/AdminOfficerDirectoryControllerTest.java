package com.scg.alumni.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AdminOfficerDirectoryControllerTest {
    @Test
    void reportsMoreRowsAndRemovesExtraRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(Map.of("recordId", 1L), Map.of("recordId", 2L)));
        var result = new AdminOfficerDirectoryController(jdbc).list("김", 0, 1);
        assertEquals(true, result.get("hasNext"));
        assertEquals(1, ((List<?>) result.get("items")).size());
    }
}
