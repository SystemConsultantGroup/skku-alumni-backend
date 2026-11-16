package com.scg.alumni.api.auth;

import com.scg.alumni.global.security.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me/login-id")
@RequiredArgsConstructor
public class MemberLoginIdController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @PatchMapping
    @Transactional
    public Map<String, Object> change(@Valid @RequestBody Request request) {
        long userId = AuthContext.currentMemberId();
        String password = jdbc.queryForObject("select password from users where id = ? and deleted_at is null for update", String.class, userId);
        if (password == null || !encoder.matches(request.currentPassword(), password)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }
        String loginId = request.loginId().trim();
        Integer occupied = jdbc.queryForObject("""
                select count(*) from users where id <> ? and (login_id = ? or student_id = ? or email = ?)
                """, Integer.class, userId, loginId, loginId, loginId);
        if (occupied != null && occupied > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Login ID is already in use");
        try {
            jdbc.update("update users set login_id = ?, updated_at = CURRENT_TIMESTAMP where id = ?", loginId, userId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Login ID is already in use", exception);
        }
        return Map.of("loginId", loginId);
    }

    public record Request(@NotBlank @Size(max = 255) String loginId, @NotBlank String currentPassword) { }
}
