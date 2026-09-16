package com.scg.alumni.api.auth;

import com.scg.alumni.global.security.PasswordPolicy;
import com.scg.alumni.global.security.PasswordResetLinkService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사무처가 보낸 재설정 링크를 연 회원이 새 비밀번호를 정한다. 로그인 없이 열린다.
 *
 * <p>토큰을 주소 경로가 아니라 본문으로 받는다. 경로에 실으면 접근 로그마다 살아 있는
 * 토큰이 남는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/member/password-reset")
public class PasswordResetController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final PasswordResetLinkService passwordResetLinkService;

    /** 링크가 살아 있는지, 누구의 비밀번호인지. 남의 링크를 받은 사람이 알아챌 수 있게 이름을 보여준다. */
    @PostMapping("/verify")
    @Transactional(readOnly = true)
    public Map<String, Object> verify(@Valid @RequestBody TokenRequest request) {
        return accountResponse(requireResettable(request.token()));
    }

    @PostMapping("/confirm")
    @Transactional
    public Map<String, Object> confirm(@Valid @RequestBody ConfirmRequest request) {
        ResettableAccount account = requireResettable(request.token());
        jdbcTemplate.update("""
                update users
                set password = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, passwordEncoder.encode(request.password()), account.id());
        passwordResetLinkService.consume(request.token(), account.id());
        return accountResponse(account);
    }

    /**
     * 토큰이 살아 있고, 그 사이 회원이 지워지거나 탈퇴하지 않았는지 본다.
     *
     * <p>발급할 때 한 번 봤어도 다시 본다. 수명이 사흘이라 그동안 회원 상태가 바뀔 수 있다.
     */
    private ResettableAccount requireResettable(String token) {
        Long memberId = passwordResetLinkService.find(token).orElseThrow(this::expired);
        return jdbcTemplate.query("""
                        select id, name, login_id, student_id
                        from users
                        where id = ? and deleted_at is null and status <> 'WITHDRAWN'
                        """,
                (resultSet, rowNum) -> new ResettableAccount(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("login_id"),
                        resultSet.getString("student_id")
                ),
                memberId)
                .stream()
                .findFirst()
                .orElseThrow(this::expired);
    }

    private Map<String, Object> accountResponse(ResettableAccount account) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", account.name());
        // 명단으로만 올라온 회원은 아이디가 없어 학번으로 들어간다. 비밀번호를 정하고도
        // 무엇으로 로그인하는지 모르면 링크를 보낸 보람이 없다.
        response.put("loginId", account.loginId());
        response.put("studentId", account.studentId());
        return response;
    }

    /** 만료·사용됨·다시 발급됨을 가르지 않는다. 회원이 할 일은 어느 경우든 같다. */
    private ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.GONE,
                "만료되었거나 이미 사용한 링크입니다. 총동창회 사무처(02-741-4171)에 새 링크를 요청해주세요.");
    }

    public record TokenRequest(@NotBlank String token) {
    }

    public record ConfirmRequest(
            @NotBlank String token,
            @NotBlank
            @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.MESSAGE)
            String password
    ) {
    }

    private record ResettableAccount(Long id, String name, String loginId, String studentId) {
    }
}
