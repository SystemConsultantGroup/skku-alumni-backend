package com.scg.alumni.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class UnifiedSearchIndexFallbackTest {
    @Test void missingFulltextIndexFallsBackToLike() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("search", "sql", new SQLException("missing index", "HY000", 1191)))
                .thenReturn(List.of(Map.of("id", 1)));
        var repository = new UnifiedSearchRepository(jdbc, new MockEnvironment().withProperty("search.mysql-ngram-enabled", "true"));
        assertThat(repository.posts("동문", 1, null, null, 10)).containsExactly(Map.of("id", 1));
    }

    @Test void unrelatedDatabaseFailureIsNotHidden() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var failure = new BadSqlGrammarException("search", "sql", new SQLException("other", "HY000", 999));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(failure);
        var repository = new UnifiedSearchRepository(jdbc, new MockEnvironment().withProperty("search.mysql-ngram-enabled", "true"));
        assertThatThrownBy(() -> repository.posts("동문", 1, null, null, 10)).isSameAs(failure);
    }
}
