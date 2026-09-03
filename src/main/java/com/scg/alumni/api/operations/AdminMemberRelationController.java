package com.scg.alumni.api.operations;

import com.scg.alumni.api.common.StoredImageUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 회원 상세 화면의 각 묶음을 직접 손보는 창구.
 *
 * <p>취미·웹링크·동호회·임원 이력·회비·게시글·차단·기기·변경 이력을 한 화면에서
 * 고칠 수 있어야 사무처가 DB를 열지 않는다.
 *
 * <p>삭제는 전부 deleted_at 을 채운다. 되살리기는 같은 값을 다시 등록하면
 * 지운 행이 살아나는 방식으로 처리한다. 유일 제약이 걸린 묶음은 새 행을 만들
 * 수 없어서 그 길밖에 없다.
 *
 * <p>기기 토큰은 등록을 두지 않았다. 토큰은 앱이 FCM 에서 받아오는 값이라
 * 사무처가 지어낼 수 있는 것이 아니다. 잘못 붙은 기기를 떼는 일만 필요하다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/{memberId}")
public class AdminMemberRelationController {

    private final JdbcTemplate jdbcTemplate;
    private final AdminAuditLog adminAuditLog;

    // 취미 -----------------------------------------------------------------

    @PostMapping("/hobbies")
    @Transactional
    public Map<String, Object> addHobby(
            @PathVariable Long memberId,
            @Valid @RequestBody HobbyLinkRequest request
    ) {
        requireMember(memberId);
        requireRow("hobbies", request.hobbyId(), "취미를 찾을 수 없습니다.");
        // 유일 제약(user_id, hobby_id) 때문에 지운 행이 있으면 새로 넣을 수 없다.
        int revived = jdbcTemplate.update("""
                update user_hobbies
                set deleted_at = null, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and hobby_id = ?
                """, memberId, request.hobbyId());
        if (revived == 0) {
            jdbcTemplate.update("""
                    insert into user_hobbies (user_id, hobby_id, created_at, updated_at)
                    values (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, memberId, request.hobbyId());
        }
        adminAuditLog.record("ADD_MEMBER_HOBBY", "user_hobby", memberId);
        return Map.of("memberId", memberId, "hobbyId", request.hobbyId());
    }

    @DeleteMapping("/hobbies/{id}")
    @Transactional
    public Map<String, Object> removeHobby(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("user_hobbies", "user_id", memberId, id, "REMOVE_MEMBER_HOBBY", "user_hobby");
        return Map.of("id", id, "deleted", true);
    }

    // 웹 링크 --------------------------------------------------------------

    @PostMapping("/web-links")
    @Transactional
    public Map<String, Object> addWebLink(
            @PathVariable Long memberId,
            @Valid @RequestBody WebLinkRequest request
    ) {
        requireMember(memberId);
        String url = validateWebLink(request.url());
        jdbcTemplate.update("""
                insert into user_web_links (user_id, url, created_at, updated_at)
                values (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, memberId, url);
        adminAuditLog.record("ADD_MEMBER_WEB_LINK", "user_web_link", memberId);
        return Map.of("memberId", memberId, "url", url);
    }

    @PatchMapping("/web-links/{id}")
    @Transactional
    public Map<String, Object> updateWebLink(
            @PathVariable Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody WebLinkRequest request
    ) {
        String url = validateWebLink(request.url());
        int updated = jdbcTemplate.update("""
                update user_web_links
                set url = ?, updated_at = CURRENT_TIMESTAMP
                where id = ? and user_id = ? and deleted_at is null
                """, url, id, memberId);
        requireUpdated(updated, "웹 링크를 찾을 수 없습니다.");
        adminAuditLog.record("UPDATE_MEMBER_WEB_LINK", "user_web_link", id);
        return Map.of("id", id, "url", url);
    }

    @DeleteMapping("/web-links/{id}")
    @Transactional
    public Map<String, Object> removeWebLink(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("user_web_links", "user_id", memberId, id, "REMOVE_MEMBER_WEB_LINK", "user_web_link");
        return Map.of("id", id, "deleted", true);
    }

    // 동호회 가입 ----------------------------------------------------------

    @PostMapping("/club-memberships")
    @Transactional
    public Map<String, Object> addClubMembership(
            @PathVariable Long memberId,
            @Valid @RequestBody ClubMembershipRequest request
    ) {
        requireMember(memberId);
        requireRow("clubs", request.clubId(), "동호회를 찾을 수 없습니다.");
        String clubRole = normalizeClubRole(request.clubRole());
        int revived = jdbcTemplate.update("""
                update club_members
                set club_role = ?, left_at = null, deleted_at = null, joined_at = CURRENT_TIMESTAMP
                where club_id = ? and user_id = ?
                """, clubRole, request.clubId(), memberId);
        if (revived == 0) {
            jdbcTemplate.update("""
                    insert into club_members (club_id, user_id, club_role, joined_at)
                    values (?, ?, ?, CURRENT_TIMESTAMP)
                    """, request.clubId(), memberId, clubRole);
        }
        adminAuditLog.record("ADD_CLUB_MEMBERSHIP", "club_member", memberId);
        return Map.of("memberId", memberId, "clubId", request.clubId(), "clubRole", clubRole);
    }

    @PatchMapping("/club-memberships/{id}")
    @Transactional
    public Map<String, Object> updateClubMembership(
            @PathVariable Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody ClubMembershipUpdateRequest request
    ) {
        String clubRole = normalizeClubRole(request.clubRole());
        int updated = jdbcTemplate.update("""
                update club_members
                set club_role = ?, left_at = ?
                where id = ? and user_id = ? and deleted_at is null
                """, clubRole, request.leftAt(), id, memberId);
        requireUpdated(updated, "동호회 가입 정보를 찾을 수 없습니다.");
        adminAuditLog.record("UPDATE_CLUB_MEMBERSHIP", "club_member", id);
        return Map.of("id", id, "clubRole", clubRole);
    }

    @DeleteMapping("/club-memberships/{id}")
    @Transactional
    public Map<String, Object> removeClubMembership(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("club_members", "user_id", memberId, id, "REMOVE_CLUB_MEMBERSHIP", "club_member");
        return Map.of("id", id, "deleted", true);
    }

    // 임원 이력 ------------------------------------------------------------

    @PostMapping("/officer-histories")
    @Transactional
    public Map<String, Object> addOfficerHistory(
            @PathVariable Long memberId,
            @Valid @RequestBody OfficerHistoryRequest request
    ) {
        requireMember(memberId);
        requireRow("officer_terms", request.officerTermId(), "임기를 찾을 수 없습니다.");
        requireRow("officer_roles", request.officerRoleId(), "직책을 찾을 수 없습니다.");
        String paymentStatus = normalizePaymentStatus(request.paymentStatus());
        LocalDate startedAt = request.startedAt() != null ? request.startedAt() : termStartedAt(request.officerTermId());
        // 유일 제약(user_id, officer_term_id). 같은 임기가 이미 있으면 그 행을 고친다.
        int revived = jdbcTemplate.update("""
                update officer_histories
                set officer_role_id = ?, started_at = ?, ended_at = ?, payment_status = ?,
                    deleted_at = null, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and officer_term_id = ?
                """, request.officerRoleId(), startedAt, request.endedAt(), paymentStatus,
                memberId, request.officerTermId());
        if (revived == 0) {
            jdbcTemplate.update("""
                    insert into officer_histories (
                        user_id, officer_term_id, officer_role_id, started_at, ended_at,
                        payment_status, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, memberId, request.officerTermId(), request.officerRoleId(),
                    startedAt, request.endedAt(), paymentStatus);
        }
        adminAuditLog.record("ADD_OFFICER_HISTORY", "officer_history", memberId);
        return Map.of("memberId", memberId, "officerTermId", request.officerTermId());
    }

    @PatchMapping("/officer-histories/{id}")
    @Transactional
    public Map<String, Object> updateOfficerHistory(
            @PathVariable Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody OfficerHistoryUpdateRequest request
    ) {
        requireRow("officer_roles", request.officerRoleId(), "직책을 찾을 수 없습니다.");
        String paymentStatus = normalizePaymentStatus(request.paymentStatus());
        int updated = jdbcTemplate.update("""
                update officer_histories
                set officer_role_id = ?, started_at = ?, ended_at = ?, payment_status = ?,
                    updated_at = CURRENT_TIMESTAMP
                where id = ? and user_id = ? and deleted_at is null
                """, request.officerRoleId(), request.startedAt(), request.endedAt(), paymentStatus, id, memberId);
        requireUpdated(updated, "임원 이력을 찾을 수 없습니다.");
        adminAuditLog.record("UPDATE_OFFICER_HISTORY", "officer_history", id);
        return Map.of("id", id, "paymentStatus", paymentStatus);
    }

    @DeleteMapping("/officer-histories/{id}")
    @Transactional
    public Map<String, Object> removeOfficerHistory(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("officer_histories", "user_id", memberId, id, "REMOVE_OFFICER_HISTORY", "officer_history");
        return Map.of("id", id, "deleted", true);
    }

    // 회비 -----------------------------------------------------------------

    @PostMapping("/payments")
    @Transactional
    public Map<String, Object> addPayment(
            @PathVariable Long memberId,
            @Valid @RequestBody PaymentRequest request
    ) {
        requireMember(memberId);
        requireRow("officer_terms", request.officerTermId(), "임기를 찾을 수 없습니다.");
        String status = normalizePaymentStatus(request.status());
        int revived = jdbcTemplate.update("""
                update payment_records
                set amount = ?, status = ?,
                    paid_at = case when ? = 'PAID' then CURRENT_TIMESTAMP else null end,
                    deleted_at = null, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and officer_term_id = ?
                """, request.amount(), status, status, memberId, request.officerTermId());
        if (revived == 0) {
            jdbcTemplate.update("""
                    insert into payment_records (
                        user_id, officer_term_id, amount, status, paid_at, created_at, updated_at
                    ) values (?, ?, ?, ?, case when ? = 'PAID' then CURRENT_TIMESTAMP else null end,
                              CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, memberId, request.officerTermId(), request.amount(), status, status);
        }
        syncOfficerPaymentStatus(memberId, request.officerTermId(), status);
        adminAuditLog.record("ADD_PAYMENT_RECORD", "payment_record", memberId);
        return Map.of("memberId", memberId, "officerTermId", request.officerTermId(), "status", status);
    }

    @PatchMapping("/payments/{id}")
    @Transactional
    public Map<String, Object> updatePayment(
            @PathVariable Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody PaymentUpdateRequest request
    ) {
        String status = normalizePaymentStatus(request.status());
        int updated = jdbcTemplate.update("""
                update payment_records
                set amount = ?, status = ?,
                    paid_at = case when ? = 'PAID' then coalesce(paid_at, CURRENT_TIMESTAMP) else null end,
                    updated_at = CURRENT_TIMESTAMP
                where id = ? and user_id = ? and deleted_at is null
                """, request.amount(), status, status, id, memberId);
        requireUpdated(updated, "회비 내역을 찾을 수 없습니다.");
        Long officerTermId = jdbcTemplate.queryForObject(
                "select officer_term_id from payment_records where id = ?", Long.class, id);
        syncOfficerPaymentStatus(memberId, officerTermId, status);
        adminAuditLog.record("UPDATE_PAYMENT_RECORD", "payment_record", id);
        return Map.of("id", id, "status", status);
    }

    @DeleteMapping("/payments/{id}")
    @Transactional
    public Map<String, Object> removePayment(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("payment_records", "user_id", memberId, id, "REMOVE_PAYMENT_RECORD", "payment_record");
        return Map.of("id", id, "deleted", true);
    }

    // 게시글 ---------------------------------------------------------------

    @PostMapping("/posts")
    @Transactional
    public Map<String, Object> addPost(
            @PathVariable Long memberId,
            @Valid @RequestBody PostWriteRequest request
    ) {
        requireMember(memberId);
        String postKind = normalizePostKind(request.postKind());
        String status = normalizePostStatus(request.status());
        if ("CLUB".equals(postKind) && request.clubId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "동호회 글은 동호회를 지정해야 합니다.");
        }
        jdbcTemplate.update("""
                insert into posts (
                    user_id, title, body, status, post_kind, club_id, industry_id, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, memberId, request.title().trim(), StoredImageUrl.toStoredPath(request.body()), status, postKind,
                request.clubId(), request.industryId());
        adminAuditLog.record("CREATE_MEMBER_POST", "post", memberId);
        return Map.of("memberId", memberId, "postKind", postKind, "status", status);
    }

    @PatchMapping("/posts/{id}")
    @Transactional
    public Map<String, Object> updatePost(
            @PathVariable Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody PostWriteRequest request
    ) {
        String postKind = normalizePostKind(request.postKind());
        String status = normalizePostStatus(request.status());
        int updated = jdbcTemplate.update("""
                update posts
                set title = ?, body = ?, status = ?, post_kind = ?, club_id = ?, industry_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                where id = ? and user_id = ? and deleted_at is null
                """, request.title().trim(), StoredImageUrl.toStoredPath(request.body()), status, postKind,
                request.clubId(), request.industryId(), id, memberId);
        requireUpdated(updated, "게시글을 찾을 수 없습니다.");
        adminAuditLog.record("UPDATE_MEMBER_POST", "post", id);
        return Map.of("id", id, "status", status);
    }

    @DeleteMapping("/posts/{id}")
    @Transactional
    public Map<String, Object> removePost(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("posts", "user_id", memberId, id, "REMOVE_MEMBER_POST", "post");
        return Map.of("id", id, "deleted", true);
    }

    // 차단 -----------------------------------------------------------------

    @PostMapping("/blocks")
    @Transactional
    public Map<String, Object> addBlock(
            @PathVariable Long memberId,
            @Valid @RequestBody BlockRequest request
    ) {
        requireMember(memberId);
        requireMember(request.blockedId());
        if (memberId.equals(request.blockedId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 자신은 차단할 수 없습니다.");
        }
        int revived = jdbcTemplate.update("""
                update user_blocks
                set deleted_at = null, updated_at = CURRENT_TIMESTAMP
                where blocker_id = ? and blocked_id = ?
                """, memberId, request.blockedId());
        if (revived == 0) {
            jdbcTemplate.update("""
                    insert into user_blocks (blocker_id, blocked_id, created_at, updated_at)
                    values (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, memberId, request.blockedId());
        }
        adminAuditLog.record("ADD_USER_BLOCK", "user_block", memberId);
        return Map.of("memberId", memberId, "blockedId", request.blockedId());
    }

    @DeleteMapping("/blocks/{id}")
    @Transactional
    public Map<String, Object> removeBlock(@PathVariable Long memberId, @PathVariable Long id) {
        int updated = jdbcTemplate.update("""
                update user_blocks
                set deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                where id = ? and (blocker_id = ? or blocked_id = ?) and deleted_at is null
                """, id, memberId, memberId);
        requireUpdated(updated, "차단 정보를 찾을 수 없습니다.");
        adminAuditLog.record("REMOVE_USER_BLOCK", "user_block", id);
        return Map.of("id", id, "deleted", true);
    }

    // 기기 토큰 ------------------------------------------------------------

    @DeleteMapping("/device-tokens/{id}")
    @Transactional
    public Map<String, Object> removeDeviceToken(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("device_tokens", "user_id", memberId, id, "REMOVE_DEVICE_TOKEN", "device_token");
        return Map.of("id", id, "deleted", true);
    }

    // 프로필 변경 이력 -----------------------------------------------------

    @PostMapping("/profile-change-logs")
    @Transactional
    public Map<String, Object> addProfileChangeLog(
            @PathVariable Long memberId,
            @Valid @RequestBody ProfileChangeLogRequest request
    ) {
        requireMember(memberId);
        jdbcTemplate.update("""
                insert into profile_change_logs (
                    user_id, field_name, old_value, new_value, synced, changed_at, synced_at,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                          case when ? = true then CURRENT_TIMESTAMP else null end,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, memberId, request.fieldName().trim(), request.oldValue(), request.newValue(),
                Boolean.TRUE.equals(request.synced()), Boolean.TRUE.equals(request.synced()));
        adminAuditLog.record("CREATE_PROFILE_CHANGE_LOG", "profile_change_log", memberId);
        return Map.of("memberId", memberId, "fieldName", request.fieldName().trim());
    }

    @PatchMapping("/profile-change-logs/{id}")
    @Transactional
    public Map<String, Object> updateProfileChangeLog(
            @PathVariable Long memberId,
            @PathVariable Long id,
            @Valid @RequestBody ProfileChangeLogRequest request
    ) {
        boolean synced = Boolean.TRUE.equals(request.synced());
        int updated = jdbcTemplate.update("""
                update profile_change_logs
                set field_name = ?, old_value = ?, new_value = ?, synced = ?,
                    synced_at = case when ? = true then coalesce(synced_at, CURRENT_TIMESTAMP) else null end,
                    updated_at = CURRENT_TIMESTAMP
                where id = ? and user_id = ? and deleted_at is null
                """, request.fieldName().trim(), request.oldValue(), request.newValue(), synced, synced, id, memberId);
        requireUpdated(updated, "변경 이력을 찾을 수 없습니다.");
        adminAuditLog.record("UPDATE_PROFILE_CHANGE_LOG", "profile_change_log", id);
        return Map.of("id", id, "synced", synced);
    }

    @DeleteMapping("/profile-change-logs/{id}")
    @Transactional
    public Map<String, Object> removeProfileChangeLog(@PathVariable Long memberId, @PathVariable Long id) {
        softDelete("profile_change_logs", "user_id", memberId, id,
                "REMOVE_PROFILE_CHANGE_LOG", "profile_change_log");
        return Map.of("id", id, "deleted", true);
    }

    // 공통 -----------------------------------------------------------------

    /**
     * 회비 상태를 임원 이력 쪽에도 맞춘다.
     *
     * <p>납부 여부는 payment_records 와 officer_histories 두 곳에 있다. 한쪽만
     * 고치면 회원 목록의 회비 칸과 상세 화면이 서로 다른 값을 보여준다.
     */
    private void syncOfficerPaymentStatus(Long memberId, Long officerTermId, String status) {
        if (officerTermId == null) {
            return;
        }
        jdbcTemplate.update("""
                update officer_histories
                set payment_status = ?, updated_at = CURRENT_TIMESTAMP
                where user_id = ? and officer_term_id = ? and deleted_at is null
                """, status, memberId, officerTermId);
    }

    private LocalDate termStartedAt(Long officerTermId) {
        return jdbcTemplate.queryForObject(
                "select started_at from officer_terms where id = ?", LocalDate.class, officerTermId);
    }

    private void softDelete(String table, String ownerColumn, Long memberId, Long id,
                            String action, String targetType) {
        int updated = jdbcTemplate.update(
                "update " + table + " set deleted_at = CURRENT_TIMESTAMP"
                        + " where id = ? and " + ownerColumn + " = ? and deleted_at is null",
                id, memberId);
        requireUpdated(updated, "대상을 찾을 수 없습니다.");
        adminAuditLog.record(action, targetType, id);
    }

    private void requireMember(Long memberId) {
        requireRow("users", memberId, "회원을 찾을 수 없습니다.");
    }

    private void requireRow(String table, Long id, String message) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
    }

    private void requireUpdated(int updated, String message) {
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
    }

    private String validateWebLink(String value) {
        String url = value == null ? "" : value.trim();
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "웹 링크는 http 또는 https URL이어야 합니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "웹 링크 주소가 올바르지 않습니다.");
        }
        return url;
    }

    private String normalizeClubRole(String value) {
        String role = upper(value);
        return switch (role) {
            case "PRESIDENT", "MANAGER", "MEMBER" -> role;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 동호회 직책입니다.");
        };
    }

    private String normalizePaymentStatus(String value) {
        String status = upper(value);
        return switch (status) {
            case "PAID", "UNPAID" -> status;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 납부 상태입니다.");
        };
    }

    private String normalizePostKind(String value) {
        String kind = upper(value);
        return switch (kind) {
            case "NOTICE", "NEWS", "CLUB", "BUSINESS", "COMMUNITY" -> kind;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 글 종류입니다.");
        };
    }

    private String normalizePostStatus(String value) {
        String status = upper(value);
        return switch (status) {
            case "PUBLISHED", "HIDDEN" -> status;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 공개 상태입니다.");
        };
    }

    private String upper(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "값이 필요합니다.");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public record HobbyLinkRequest(@NotNull Long hobbyId) {
    }

    public record WebLinkRequest(@NotBlank @Size(max = 1000) String url) {
    }

    public record ClubMembershipRequest(@NotNull Long clubId, @NotBlank String clubRole) {
    }

    public record ClubMembershipUpdateRequest(@NotBlank String clubRole, java.time.LocalDateTime leftAt) {
    }

    public record OfficerHistoryRequest(
            @NotNull Long officerTermId,
            @NotNull Long officerRoleId,
            LocalDate startedAt,
            LocalDate endedAt,
            @NotBlank String paymentStatus
    ) {
    }

    public record OfficerHistoryUpdateRequest(
            @NotNull Long officerRoleId,
            @NotNull LocalDate startedAt,
            LocalDate endedAt,
            @NotBlank String paymentStatus
    ) {
    }

    public record PaymentRequest(
            @NotNull Long officerTermId,
            @NotNull @PositiveOrZero Integer amount,
            @NotBlank String status
    ) {
    }

    public record PaymentUpdateRequest(
            @NotNull @PositiveOrZero Integer amount,
            @NotBlank String status
    ) {
    }

    public record PostWriteRequest(
            @NotBlank @Size(max = 255) String title,
            String body,
            @NotBlank String postKind,
            @NotBlank String status,
            Long clubId,
            Long industryId
    ) {
    }

    public record BlockRequest(@NotNull Long blockedId) {
    }

    public record ProfileChangeLogRequest(
            @NotBlank @Size(max = 100) String fieldName,
            @Size(max = 255) String oldValue,
            @Size(max = 255) String newValue,
            Boolean synced
    ) {
    }
}
