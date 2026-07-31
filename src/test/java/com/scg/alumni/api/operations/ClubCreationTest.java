package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class ClubCreationTest {

    @Autowired
    private UserFeatureController userFeatureController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("alter table clubs alter column id restart with 1000");
        jdbcTemplate.execute("alter table club_members alter column id restart with 1000");
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(1L, AuthScope.MEMBER, "김명륜");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void creatorBecomesClubPresidentAndMember() {
        Map<String, Object> response = userFeatureController.createClub(
                new UserFeatureController.ClubCreateRequest(
                        "성균 러닝회", "함께 달리는 동호회입니다.", "HOBBY"));

        Long clubId = jdbcTemplate.queryForObject(
                "select id from clubs where name = ?", Long.class, "성균 러닝회");
        Long presidentUserId = jdbcTemplate.queryForObject(
                "select president_user_id from clubs where id = ?", Long.class, clubId);
        String clubRole = jdbcTemplate.queryForObject("""
                select club_role from club_members
                where club_id = ? and user_id = ?
                """, String.class, clubId, 1L);

        assertThat(response.get("id")).isEqualTo(clubId);
        assertThat(response.get("presidentUserId")).isEqualTo(1L);
        assertThat(presidentUserId).isEqualTo(1L);
        assertThat(clubRole).isEqualTo("PRESIDENT");
    }
}
