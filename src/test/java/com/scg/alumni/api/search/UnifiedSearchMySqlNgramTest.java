package com.scg.alumni.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs only against the disposable loopback database prepared for this test. */
@EnabledIfEnvironmentVariable(named = "RUN_LOCAL_SEARCH_MYSQL_TEST", matches = "true")
class UnifiedSearchMySqlNgramTest {
    @Test
    void ngramAndLikeCoverKoreanTitleBodyAuthorAndNotices() {
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13319/skku_search_test?characterEncoding=UTF-8", "search_test", "local-search-test");
        var jdbc = new JdbcTemplate(ds);
        var repo = new UnifiedSearchRepository(jdbc, new MockEnvironment().withProperty("search.mysql-ngram-enabled", "true"));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String query = "동문회관" + suffix;
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, '본문', 'PUBLISHED', 'BUSINESS')", query + " 제목");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, ?, 'PUBLISHED', 'BUSINESS')", suffix + " 본문제목", query);
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, '본문', 'PUBLISHED', 'NOTICE')", query + " 공지");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, '본문', 'HIDDEN', 'NOTICE')", query + " 숨김");
        var posts = repo.posts(query, 2, null, null, 20);
        assertThat(posts).extracting(row -> row.get("title")).contains(query + " 제목", suffix + " 본문제목");
        assertThat(((Number) posts.getFirst().get("relevance")).intValue()).isEqualTo(3);
        long firstId = ((Number) posts.getFirst().get("id")).longValue();
        assertThat(repo.posts(query, 2, firstId, 3, 20)).extracting(row -> row.get("title"))
                .contains(suffix + " 본문제목").doesNotContain(query + " 제목");
        assertThat(repo.notices(query, 2, null, null, 20)).extracting(row -> row.get("title")).contains(query + " 공지").doesNotContain(query + " 숨김");
        assertThat(repo.notices("가상 작성자", 2, null, null, 100)).extracting(row -> row.get("title")).contains(query + " 공지");
        assertThat(repo.notices("동", 2, null, null, 100)).extracting(row -> row.get("title")).contains(query + " 공지");
        assertThat(repo.notices("회관", 2, null, null, 100)).extracting(row -> row.get("title")).contains(query + " 공지");
        assertThat(repo.notices("회관예약", 2, null, null, 100)).extracting(row -> row.get("title")).contains(query + " 공지");
        jdbc.update("insert into user_blocks (blocker_id, blocked_id) values (987654, 1)");
        assertThat(repo.notices(query, 987654, null, null, 20)).isEmpty();
    }
}
