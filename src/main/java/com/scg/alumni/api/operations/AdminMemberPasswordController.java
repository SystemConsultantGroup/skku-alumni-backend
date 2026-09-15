package com.scg.alumni.api.operations;

import com.scg.alumni.global.security.AuthProperties;
import com.scg.alumni.global.security.PasswordPolicy;
import com.scg.alumni.global.security.PasswordResetLinkService;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사무처가 회원의 비밀번호를 정해 준다.
 *
 * <p>지금까지 비밀번호를 잊은 임원에게 해 줄 수 있는 것이 없었다. 본인이 다시
 * 정하게 하려면 메일이나 문자로 링크를 보내야 하는데 그 통로가 아직 없다. 그
 * 사이에도 전화로 문의가 들어오므로, 사무처가 새 비밀번호를 넣어 주고 본인에게
 * 직접 알리는 길을 먼저 연다.
 *
 * <p>새 비밀번호는 사무처가 적는다. 서버가 정해진 값으로 되돌리는 방식이면 그
 * 값이 코드에 박혀 누구나 아는 비밀번호가 된다.
 *
 * <p>지운 회원과 탈퇴 회원은 대상이 아니다. 탈퇴하면 이름·학번까지 비워지므로
 * 비밀번호를 넣어도 로그인할 수 있는 계정이 되지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/{memberId}/password")
public class AdminMemberPasswordController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditLog adminAuditLog;
    private final PasswordResetLinkService passwordResetLinkService;
    private final AuthProperties authProperties;

    @PostMapping
    @Transactional
    public Map<String, Object> resetPassword(
            @PathVariable Long memberId,
            @Valid @RequestBody PasswordResetRequest request
    ) {
        MemberAccount account = findResettableAccount(memberId);

        jdbcTemplate.update("""
                update users
                set password = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, passwordEncoder.encode(request.password()), memberId);
        // 무엇으로 바꿨는지는 남기지 않는다. 감사 로그는 사무처 전원이 본다.
        adminAuditLog.record("RESET_MEMBER_PASSWORD", "user", memberId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", memberId);
        response.put("name", account.name());
        // 로그인 창에 무엇을 넣어야 하는지 함께 돌려준다. 아이디를 만든 적 없는
        // 회원은 학번으로 들어가야 하는데, 그것을 모르면 초기화해 주고도
        // "로그인이 안 된다"는 문의를 다시 받는다.
        response.put("loginId", account.loginId());
        response.put("studentId", account.studentId());
        response.put("status", account.status());
        return response;
    }

    /**
     * 재설정 링크를 만들어 사무처에 돌려준다. 보내는 일은 사무처가 한다.
     *
     * <p>메일 발송을 붙이지 않은 이유: 명부로 올라온 회원 상당수는 이메일이 없거나
     * 낡았고, 사무처는 이미 문자·카카오톡으로 회원과 연락한다. 링크만 있으면 어느
     * 통로로든 보낼 수 있다. 연락처를 함께 돌려주는 것도 그 때문이다.
     *
     * <p>새로 만들면 앞서 만든 링크는 죽는다. 엉뚱한 사람에게 보냈으면 다시 누르면 된다.
     */
    @PostMapping("/reset-link")
    @Transactional
    public Map<String, Object> issueResetLink(@PathVariable Long memberId) {
        MemberAccount account = findResettableAccount(memberId);
        String token = passwordResetLinkService.issue(memberId);
        adminAuditLog.record("ISSUE_MEMBER_PASSWORD_RESET_LINK", "user", memberId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", memberId);
        response.put("name", account.name());
        // 토큰은 # 뒤에 싣는다. 조각은 서버로도, 다른 사이트로 가는 Referer 로도,
        // 페이지 분석 도구의 수집 주소로도 넘어가지 않는다.
        response.put("url", memberWebUrl() + "/reset-password#" + token);
        response.put("expiresAt", Instant.now().plus(passwordResetLinkService.ttl()).toString());
        response.put("loginId", account.loginId());
        response.put("studentId", account.studentId());
        response.put("phone", account.phone());
        response.put("email", account.email());
        return response;
    }

    private String memberWebUrl() {
        String url = authProperties.getMemberWebUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private MemberAccount findResettableAccount(Long memberId) {
        MemberAccount account = findAccount(memberId);
        if (account.deleted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "삭제된 회원입니다. 먼저 회원을 복구한 뒤 비밀번호를 초기화해주세요.");
        }
        if ("WITHDRAWN".equals(account.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "탈퇴한 회원입니다. 다시 이용하시려면 임원 등록을 새로 신청해야 합니다.");
        }
        return account;
    }

    private MemberAccount findAccount(Long memberId) {
        return jdbcTemplate.query("""
                        select id, name, login_id, student_id, phone, email, status, deleted_at
                        from users
                        where id = ?
                        """,
                (resultSet, rowNum) -> new MemberAccount(
                        resultSet.getString("name"),
                        resultSet.getString("login_id"),
                        resultSet.getString("student_id"),
                        resultSet.getString("phone"),
                        resultSet.getString("email"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("deleted_at") != null
                ),
                memberId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
    }

    public record PasswordResetRequest(
            @NotBlank
            @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
            String password
    ) {
    }

    private record MemberAccount(
            String name,
            String loginId,
            String studentId,
            String phone,
            String email,
            String status,
            boolean deleted
    ) {
    }
}
