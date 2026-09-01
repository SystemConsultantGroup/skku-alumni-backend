package com.scg.alumni.api.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scg.alumni.api.member.MemberDirectoryService;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import java.util.List;
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
class AdminMemberDetailTest {

    @Autowired
    private AdminMemberDetailController adminMemberDetailController;

    @Autowired
    private AdminMemberRelationController adminMemberRelationController;

    @Autowired
    private MemberDirectoryService memberDirectoryService;

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
    void detailGathersEverythingLinkedToTheMember() {
        Map<String, Object> detail = adminMemberDetailController.findMember(1L);

        assertThat(profileOf(detail).get("name")).isEqualTo("박성균");
        assertThat(profileOf(detail).get("majorName")).isEqualTo("법률학과");
        assertThat(listOf(detail, "hobbies")).extracting(row -> row.get("name"))
                .containsExactlyInAnyOrder("골프", "바둑");
        assertThat(listOf(detail, "clubMemberships")).isNotEmpty();
        assertThat(listOf(detail, "officerHistories")).isNotEmpty();
        assertThat(listOf(detail, "payments")).isNotEmpty();
        assertThat(detail).containsKeys("webLinks", "posts", "reportsFiled", "reportsReceived",
                "blocks", "blockedBy", "deviceTokens", "profileChangeLogs", "clubApplications");
    }

    /** 지운 취미는 상세에서도, 동문 검색의 취미 필터에서도 빠져야 한다. */
    @Test
    @Transactional
    void deletedHobbyDisappearsFromDetailAndDirectory() {
        Long linkId = jdbcTemplate.queryForObject(
                "select id from user_hobbies where user_id = 1 and hobby_id = 1", Long.class);

        adminMemberRelationController.removeHobby(1L, linkId);

        assertThat(listOf(adminMemberDetailController.findMember(1L), "hobbies"))
                .extracting(row -> row.get("name"))
                .containsExactly("바둑");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_hobbies where id = ?", Integer.class, linkId)).isOne();
        assertThat(directoryIdsWithHobby(1L)).doesNotContain(1L);
    }

    /** 지운 취미를 다시 붙이면 새 행이 아니라 지운 행이 살아난다. */
    @Test
    @Transactional
    void addingBackADeletedHobbyRevivesTheSameRow() {
        Long linkId = jdbcTemplate.queryForObject(
                "select id from user_hobbies where user_id = 1 and hobby_id = 1", Long.class);
        adminMemberRelationController.removeHobby(1L, linkId);

        adminMemberRelationController.addHobby(1L,
                new AdminMemberRelationController.HobbyLinkRequest(1L));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_hobbies where user_id = 1 and hobby_id = 1",
                Integer.class)).isOne();
        assertThat(listOf(adminMemberDetailController.findMember(1L), "hobbies"))
                .extracting(row -> row.get("hobbyId"))
                .contains(1L);
    }

    /** 회원 삭제는 행을 남긴다. 되살리면 데이터가 그대로 돌아와야 한다. */
    @Test
    @Transactional
    void deletingAMemberKeepsTheRowAndCanBeUndone() {
        adminMemberDetailController.deleteMember(1L);

        assertThat(profileOf(adminMemberDetailController.findMember(1L)).get("deletedAt")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select name from users where id = 1", String.class)).isEqualTo("박성균");
        assertThat(directoryIds()).doesNotContain(1L);
        assertThat(memberIdsInAdminList(false)).doesNotContain(1L);
        assertThat(memberIdsInAdminList(true)).contains(1L);

        adminMemberDetailController.restoreMember(1L);

        assertThat(profileOf(adminMemberDetailController.findMember(1L)).get("deletedAt")).isNull();
        assertThat(memberIdsInAdminList(false)).contains(1L);
    }

    @Test
    @Transactional
    void unknownMemberIsReportedAsNotFound() {
        assertThatThrownBy(() -> adminMemberDetailController.findMember(9999L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("회원을 찾을 수 없습니다");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> profileOf(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("profile");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> detail, String key) {
        return (List<Map<String, Object>>) detail.get(key);
    }

    private List<Long> directoryIds() {
        return memberDirectoryService.search(null, null, null, null, null, null, null, null, null, null, null, 50)
                .items().stream().map(item -> item.id()).toList();
    }

    private List<Long> directoryIdsWithHobby(Long hobbyId) {
        return memberDirectoryService.search(null, null, null, null, null, null, null, null, null, hobbyId, null, 50)
                .items().stream().map(item -> item.id()).toList();
    }

    private List<Long> memberIdsInAdminList(boolean includeDeleted) {
        return jdbcTemplate.queryForList("""
                select id from users
                where (? = true or deleted_at is null)
                """, Long.class, includeDeleted);
    }
}
