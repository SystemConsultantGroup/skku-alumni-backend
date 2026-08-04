package com.scg.alumni.api.auth;

import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.global.security.AuthProperties;
import com.scg.alumni.global.security.AuthScope;
import com.scg.alumni.global.security.AuthenticatedPrincipal;
import com.scg.alumni.global.security.JwtTokenService;
import com.scg.alumni.global.security.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AuthProperties authProperties;

    @PostMapping("/member/login")
    @Transactional(readOnly = true)
    public AuthResponse memberLogin(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        MemberCredential credential = findMemberCredential(request.identifier());
        if (!passwordEncoder.matches(request.password(), credential.password())) {
            throw unauthorized();
        }

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(credential.id(), AuthScope.MEMBER, credential.name());
        return issueAuthResponse(principal, httpRequest, httpResponse);
    }

    @PostMapping("/member/signup")
    @Transactional
    public SignupResponse memberSignup(@Valid @RequestBody SignupRequest request) {
        SignupCandidate candidate = findSignupCandidate(request);
        if (!confirmationMatches(candidate, request)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 동문 정보를 찾을 수 없습니다.");
        }
        validateSignupCandidate(candidate, request);

        jdbcTemplate.update("""
                        update users
                        set kingo_id = ?,
                            password = ?,
                            phone = coalesce(phone, ?),
                            status = 'PENDING',
                            updated_at = CURRENT_TIMESTAMP
                        where id = ?
                        """,
                request.userId().trim(), passwordEncoder.encode(request.password()), request.phone(), candidate.id());

        return new SignupResponse(candidate.id(), candidate.name(), AuthScope.MEMBER, "PENDING");
    }

    @PostMapping("/admin/login")
    @Transactional(readOnly = true)
    public AuthResponse adminLogin(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AdminCredential credential = findAdminCredential(request.identifier());
        if (!passwordEncoder.matches(request.password(), credential.password())) {
            throw unauthorized();
        }

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(credential.id(), AuthScope.ADMIN, credential.name());
        return issueAuthResponse(principal, httpRequest, httpResponse);
    }

    @PostMapping("/member/refresh")
    public AuthResponse memberRefresh(HttpServletRequest request, HttpServletResponse response) {
        return refresh(AuthScope.MEMBER, request, response);
    }

    @PostMapping("/admin/refresh")
    public AuthResponse adminRefresh(HttpServletRequest request, HttpServletResponse response) {
        return refresh(AuthScope.ADMIN, request, response);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        revokeAndClear(AuthScope.MEMBER, request, response);
        revokeAndClear(AuthScope.ADMIN, request, response);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public AuthenticatedPrincipal me() {
        return AuthContext.current();
    }

    @GetMapping("/member/me")
    public AuthenticatedPrincipal memberMe() {
        AuthenticatedPrincipal principal = AuthContext.current();
        if (principal.scope() != AuthScope.MEMBER) {
            throw unauthorized();
        }
        return principal;
    }

    @GetMapping("/admin/me")
    public AuthenticatedPrincipal adminMe() {
        AuthenticatedPrincipal principal = AuthContext.current();
        if (principal.scope() != AuthScope.ADMIN) {
            throw unauthorized();
        }
        return principal;
    }

    private AuthResponse refresh(AuthScope scope, HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieValue(request, authProperties.refreshCookieName(scope));
        if (refreshToken == null) {
            throw unauthorized();
        }

        AuthenticatedPrincipal principal = refreshTokenService.consume(scope, refreshToken)
                .orElseThrow(this::unauthorized);
        if (scope == AuthScope.MEMBER && !isActiveMember(principal.id())) {
            revokeAndClear(AuthScope.MEMBER, request, response);
            throw unauthorized();
        }
        return issueAuthResponse(principal, request, response);
    }

    private boolean isActiveMember(Long memberId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where id = ? and status = 'ACTIVE'",
                Integer.class,
                memberId);
        return count != null && count > 0;
    }

    private AuthResponse issueAuthResponse(
            AuthenticatedPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        JwtTokenService.IssuedAccessToken accessToken = jwtTokenService.issue(principal);
        String refreshToken = refreshTokenService.issue(principal);

        addCookie(response, authProperties.accessCookieName(principal.scope()), accessToken.token(),
                authProperties.getAccessTokenTtl(), request.isSecure());
        addCookie(response, authProperties.refreshCookieName(principal.scope()), refreshToken,
                authProperties.getRefreshTokenTtl(), request.isSecure());

        return new AuthResponse(principal.scope(), principal.id(), principal.name(), accessToken.expiresAt().toString());
    }

    private MemberCredential findMemberCredential(String identifier) {
        return jdbcTemplate.query("""
                        select id, name, password
                        from users
                        where status = 'ACTIVE'
                          and password is not null
                          and password <> ''
                          and (student_id = ? or kingo_id = ? or email = ?)
                        order by id
                        limit 1
                        """,
                (resultSet, rowNum) -> new MemberCredential(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("password")
                ),
                identifier, identifier, identifier)
                .stream()
                .findFirst()
                .orElseThrow(this::unauthorized);
    }

    private AdminCredential findAdminCredential(String identifier) {
        return jdbcTemplate.query("""
                        select id, name, password
                        from admins
                        where active = true
                          and email = ?
                        order by id
                        limit 1
                        """,
                (resultSet, rowNum) -> new AdminCredential(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("password")
                ),
                identifier)
                .stream()
                .findFirst()
                .orElseThrow(this::unauthorized);
    }

    private SignupCandidate findSignupCandidate(SignupRequest request) {
        return jdbcTemplate.query("""
                        select u.id, u.name, u.phone, u.email, u.status, u.kingo_id, u.password
                        from users u
                        join majors m on m.id = u.major_id
                        left join majors dm on dm.id = m.display_major_id
                        where u.name = ?
                          and u.admission_year = ?
                          and (
                              lower(replace(m.name, ' ', '')) = ?
                              or lower(replace(m.normalized_name, ' ', '')) = ?
                              or lower(replace(coalesce(dm.name, ''), ' ', '')) = ?
                              or lower(replace(coalesce(dm.normalized_name, ''), ' ', '')) = ?
                          )
                          and replace(replace(replace(coalesce(u.phone, ''), '-', ''), ' ', ''), '.', '') = ?
                          and exists (
                              select oh.id
                              from officer_histories oh
                              join officer_terms ot on ot.id = oh.officer_term_id
                              where oh.user_id = u.id
                                and ot.current_term = true
                          )
                        order by u.id
                        limit 1
                        """,
                (resultSet, rowNum) -> new SignupCandidate(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("phone"),
                        resultSet.getString("email"),
                        resultSet.getString("status"),
                        resultSet.getString("kingo_id"),
                        resultSet.getString("password")
                ),
                request.name().trim(), request.admissionYear(),
                normalizeText(request.majorName()), normalizeText(request.majorName()),
                normalizeText(request.majorName()), normalizeText(request.majorName()),
                normalizePhone(request.phone()))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 동문 정보를 찾을 수 없습니다."));
    }

    private boolean confirmationMatches(SignupCandidate candidate, SignupRequest request) {
        return normalizePhone(candidate.phone()).equals(normalizePhone(request.phone()));
    }

    private void validateSignupCandidate(SignupCandidate candidate, SignupRequest request) {
        if ("ACTIVE".equals(candidate.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 활성화된 계정입니다. 로그인해주세요.");
        }
        if ("REJECTED".equals(candidate.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "가입 승인이 반려된 계정입니다. 사무처에 문의해주세요.");
        }
        if (hasText(candidate.userId()) && hasText(candidate.password())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 아이디와 비밀번호가 등록된 계정입니다. 회비 확인 후 활성화됩니다.");
        }
        if (userIdExists(request.userId(), candidate.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
    }

    private boolean userIdExists(String userId, Long currentUserId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from users
                where kingo_id = ?
                  and id <> ?
                """, Integer.class, userId.trim(), currentUserId);
        return count != null && count > 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("[^0-9]", "");
    }

    private String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").toLowerCase();
    }

    private void revokeAndClear(AuthScope scope, HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieValue(request, authProperties.refreshCookieName(scope));
        if (refreshToken != null) {
            refreshTokenService.revoke(scope, refreshToken);
        }
        clearCookie(response, authProperties.accessCookieName(scope), request.isSecure());
        clearCookie(response, authProperties.refreshCookieName(scope), request.isSecure());
    }

    private void addCookie(
            HttpServletResponse response,
            String name,
            String value,
            java.time.Duration maxAge,
            boolean secure
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, String name, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호를 확인해주세요.");
    }

    public record LoginRequest(
            @NotBlank String identifier,
            @NotBlank String password
    ) {
    }

    public record SignupRequest(
            @NotBlank String userId,
            @NotBlank String name,
            @NotNull Integer admissionYear,
            @NotBlank String majorName,
            @NotBlank String phone,
            @NotBlank
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$",
                    message = "비밀번호는 8자 이상이며 영문, 숫자, 특수문자를 포함해야 합니다."
            )
            String password
    ) {
    }

    public record AuthResponse(
            AuthScope scope,
            Long id,
            String name,
            String accessTokenExpiresAt
    ) {
    }

    public record SignupResponse(
            Long id,
            String name,
            AuthScope scope,
            String status
    ) {
    }

    private record MemberCredential(
            Long id,
            String name,
            String password
    ) {
    }

    private record AdminCredential(
            Long id,
            String name,
            String password
    ) {
    }

    private record SignupCandidate(
            Long id,
            String name,
            String phone,
            String email,
            String status,
            String userId,
            String password
    ) {
    }
}
