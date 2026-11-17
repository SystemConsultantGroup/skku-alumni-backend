package com.scg.alumni.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest
@Transactional
class UnifiedSearchServiceTest {
    @Autowired UnifiedSearchService search;
    @Autowired UnifiedSearchRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test void noticeAndPostSummariesShowTheMatchingBodyContext() {
        String body = "앞".repeat(300) + "가상검색어" + "뒤".repeat(300);
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, '가상 공지', ?, 'PUBLISHED', 'NOTICE')", body);
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, '가상 게시물', ?, 'PUBLISHED', 'BUSINESS')", body);
        var results = search.search("가상검색어", 2, null, null, 10);
        assertThat((String) results.notices().getFirst().get("summary")).startsWith("…").contains("가상검색어");
        assertThat((String) results.posts().getFirst().get("summary")).startsWith("…").contains("가상검색어");
        assertThat(results.posts().getFirst().get("body")).isEqualTo(body);
    }

    @Test
    void noticesAreSeparateAndPreservePublicationAndBlockRules() {
        String marker = "가상공지" + java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, '본문', 'PUBLISHED', 'NOTICE')", marker + " 제목");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, '다른 제목', ?, 'PUBLISHED', 'NOTICE')", marker + " 본문");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, '본문', 'HIDDEN', 'NOTICE')", marker + " 숨김");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, '본문', 'PUBLISHED', 'NEWS')", marker + " 뉴스");
        jdbc.update("insert into posts (user_id, title, body, status, post_kind, deleted_at) values (1, ?, '본문', 'PUBLISHED', 'NOTICE', CURRENT_TIMESTAMP)", marker + " 삭제");
        var first = search.search(marker, 2, null, null, null, null, null, 1);
        assertThat(first.notices()).extracting(row -> row.get("title")).containsExactly(marker + " 제목");
        assertThat(first.posts()).isEmpty();
        var second = search.search(marker, 2, null, null, null, first.nextNoticeCursor(), first.nextNoticeRank(), 1);
        assertThat(second.notices()).extracting(row -> row.get("title")).containsExactly("다른 제목");
        assertThat(second.nextNoticeCursor()).isNull();
        jdbc.update("insert into user_blocks (blocker_id, blocked_id) values (2, 1)");
        assertThat(search.search(marker, 2, null, null, 10).notices()).isEmpty();
    }

    @Test
    void noticeAuthorSearchAndOneCharacterFallbackWork() {
        String marker = "가상작성자" + java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("update users set name = ? where id = 1", marker);
        jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, '단일 제목', '단일 본문', 'PUBLISHED', 'NOTICE')");
        assertThat(repository.notices(marker, 2, null, null, 20)).extracting(row -> row.get("title")).contains("단일 제목");
        assertThat(repository.notices("단", 2, null, null, 20)).extracting(row -> row.get("title")).contains("단일 제목");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void searchesPostsByBodyAndHonorsPublishedStatus() {
        String marker = "searchtest" + java.util.UUID.randomUUID().toString().replace("-", "");
        String published = marker + " published";
        String hidden = marker + " hidden";
        try {
            jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, ?, 'PUBLISHED', 'BUSINESS')", published, marker);
            jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, ?, 'HIDDEN', 'BUSINESS')", hidden, marker);
            assertThat(repository.posts(marker, 2, null, 10)).extracting(row -> row.get("title")).containsExactly(published);
        } finally {
            jdbc.update("delete from posts where title in (?, ?)", published, hidden);
        }
    }

    @Test
    void pendingHistoryNeedsApprovalForOfficerSearchClassification() {
        LocalDate today = LocalDate.now();
        jdbc.update("update users set name = 'Pending Search Test', status = 'PENDING' where id = 2");
        jdbc.update("delete from officer_history_approvals where officer_history_id in (select id from officer_histories where user_id = 2)");
        jdbc.update("insert into officer_terms (generation, phase, started_at, ended_at, current_term) values (998, 1, ?, ?, false)", today.minusDays(1), today.plusDays(1));
        long term = jdbc.queryForObject("select id from officer_terms where generation = 998", Long.class);
        jdbc.update("insert into officer_histories (user_id, officer_term_id, officer_role_id, started_at) values (2, ?, 1, ?)", term, today);
        assertThat(repository.people("Pending Search Test", 1, today, null, null, 20)).isEmpty();
        jdbc.update("insert into officer_history_approvals (officer_history_id) select id from officer_histories where user_id = 2 and officer_term_id = ?", term);
        assertThat(repository.people("Pending Search Test", 1, today, null, null, 20))
                .anySatisfy(row -> assertThat(((Number) row.get("officer")).intValue()).isEqualTo(1));
    }

    @Test
    void findsCurrentOrganizationMemberButNotVipAlone() {
        jdbc.update("insert into alumni_organizations (name) values ('Fictional search club')");
        long organization = jdbc.queryForObject("select max(id) from alumni_organizations", Long.class);
        jdbc.update("insert into alumni_organization_officers (organization_id, user_id, title, president, effective_started_at) values (?, 2, 'Member', false, ?)", organization, LocalDate.now().minusDays(1));
        String name = jdbc.queryForObject("select name from users where id = 2", String.class);
        var matches = repository.people(name, 1, LocalDate.now(), null, null, 20);
        assertThat(matches).extracting(row -> ((Number) row.get("id")).longValue()).contains(2L);
        assertThat(matches.stream().filter(row -> ((Number) row.get("id")).longValue() == 2L).findFirst().orElseThrow().get("organizations"))
                .isEqualTo(java.util.List.of("Fictional search club"));
        jdbc.update("update users set name = 'Unique VIP Search', status = 'PENDING' where id = 3");
        jdbc.update("insert into vip_qualifications (user_id) values (3)");
        jdbc.update("update officer_histories set deleted_at = CURRENT_TIMESTAMP where user_id = 3");
        assertThat(repository.people("Unique VIP Search", 1, LocalDate.now(), null, null, 20)).isEmpty();
        assertThat(search.search("병원", 1, null, null, 10).shortcuts()).isNotEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ranksTitleBeforeBodyAndPaginatesAcrossRanks() {
        String marker = "rank" + java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, ?, 'PUBLISHED', 'BUSINESS')", marker + " title", "title body");
            jdbc.update("insert into posts (user_id, title, body, status, post_kind) values (1, ?, ?, 'PUBLISHED', 'BUSINESS')", "ordinary title", marker + " body");
            var first = search.search(marker, 2, null, null, null, 1);
            assertThat(first.posts().getFirst().get("title")).isEqualTo(marker + " title");
            assertThat(first.posts().getFirst().get("summary")).isEqualTo("title body");
            var second = search.search(marker, 2, first.nextPostCursor(), first.nextPostRank(), null, 1);
            assertThat(second.posts().getFirst().get("title")).isEqualTo("ordinary title");
            assertThat(second.nextPostCursor()).isNull();
        } finally {
            jdbc.update("delete from posts where title = ? or body = ?", marker + " title", marker + " body");
        }
    }

    @Test
    void centralOfficerIsFirstAndNotDuplicatedAcrossOrganizations() {
        jdbc.update("update users set name = 'Fictional Search Person' where id in (2, 3)");
        jdbc.update("insert into alumni_organizations (name) values ('Fictional first'), ('Fictional second')");
        var orgs = jdbc.queryForList("select id from alumni_organizations where name like 'Fictional %' order by id", Long.class);
        for (Long org : orgs) {
            jdbc.update("insert into alumni_organization_officers (organization_id, user_id, title, president, effective_started_at) values (?, 3, 'Member', false, ?)", org, LocalDate.now().minusDays(1));
        }
        var matches = repository.people("fictional search person", 1, LocalDate.now(), null, null, 20);
        assertThat(matches).extracting(row -> ((Number) row.get("id")).longValue()).containsOnlyOnce(3L);
        assertThat(((Number) matches.getFirst().get("officer")).intValue()).isEqualTo(1);
        assertThat(matches.stream().filter(row -> ((Number) row.get("id")).longValue() == 3L)
                .findFirst().orElseThrow().get("organizations")).isEqualTo(java.util.List.of("Fictional first", "Fictional second"));
    }
}
