package com.scg.alumni.api.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminMemberAccessControllerTest {
    @Test
    void returnsAuthoritativeLevelAndBlockedState() {
        var qualifications = mock(AccessQualificationService.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select account_blocked from users where id = ?", 7L))
                .thenReturn(List.of(Map.of("account_blocked", 1)));
        when(qualifications.currentLevel(7L)).thenReturn(AccessLevel.NONE);
        var result = new AdminMemberAccessController(qualifications, jdbc).access(7L);
        assertEquals(AccessLevel.NONE, result.level());
        assertEquals(true, result.blocked());
        assertEquals(AccessLevel.NONE.permissions(), result.permissions());
    }

    @Test
    void rejectsMissingMember() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select account_blocked from users where id = ?", 9L)).thenReturn(List.of());
        var controller = new AdminMemberAccessController(mock(AccessQualificationService.class), jdbc);
        assertThrows(ResponseStatusException.class, () -> controller.access(9L));
    }
}
