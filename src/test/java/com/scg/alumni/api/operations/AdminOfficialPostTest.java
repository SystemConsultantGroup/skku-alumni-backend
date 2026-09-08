package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class AdminOfficialPostTest {

    @Autowired
    private AdminFeatureController adminFeatureController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void signInAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedPrincipal(1L, AuthScope.ADMIN, "안상인"), null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @Transactional
    void deletedOfficialPostLeavesTheContentList() {
        Long id = createNotice("지난 총회 안내");

        assertThat(postIdsInContentList()).contains(id);

        adminFeatureController.removeOfficialPost(id);

        assertThat(postIdsInContentList()).doesNotContain(id);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from posts where id = ? and deleted_at is not null", Integer.class, id)).isOne();
    }

    /** 남의 글은 회원 상세에서 지운다. 여기서 지우면 누구 글을 지웠는지 기록이 흩어진다. */
    @Test
    @Transactional
    void memberPostIsNotDeletableFromTheContentScreen() {
        Long id = jdbcTemplate.queryForObject(
                "select id from posts where post_kind not in ('NOTICE', 'NEWS') and deleted_at is null order by id limit 1",
                Long.class);

        assertThatThrownBy(() -> adminFeatureController.removeOfficialPost(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("삭제할 공지/뉴스를 찾을 수 없습니다");
    }

    @Test
    @Transactional
    void deletedOfficialPostCannotBeEditedOrRepublished() {
        Long id = createNotice("잘못 올린 공지");
        adminFeatureController.removeOfficialPost(id);

        assertThatThrownBy(() -> adminFeatureController.updateOfficialPost(id,
                new AdminFeatureController.OfficialPostWriteRequest("NOTICE", "되살린 공지", "내용")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> adminFeatureController.updatePostStatus(id,
                new AdminFeatureController.StatusUpdateRequest("PUBLISHED")))
                .isInstanceOf(ResponseStatusException.class);
    }

    private Long createNotice(String title) {
        Map<String, Object> created = adminFeatureController.createOfficialPost(
                new AdminFeatureController.OfficialPostWriteRequest("NOTICE", title, "본문"));
        return ((Number) created.get("id")).longValue();
    }

    private java.util.List<Long> postIdsInContentList() {
        return adminFeatureController.findPosts(null, null, null, null, null, 100).items()
                .stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();
    }
}
