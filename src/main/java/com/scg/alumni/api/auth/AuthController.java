package com.scg.alumni.api.auth;

import java.util.List;
import com.scg.alumni.domain.academic.MajorNames;
import com.scg.alumni.global.security.AdminRoleGuard;
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
import java.util.LinkedHashMap;
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
    /** 어느 학과에도 없는 id. 후보가 없을 때 in () 문법 오류를 피한다. */
    private static final long NO_MAJOR = -1L;

    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AuthProperties authProperties;
    private final AdminRoleGuard adminRoleGuard;

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
        requireLoginableStatus(credential.status());

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

    /**
     * 관리 화면이 역할에 맞는 메뉴만 보여줄 수 있도록 권한을 함께 내려준다.
     *
     * <p>접근 차단 자체는 서버가 하지만, 근로장학생에게 쓸 수 없는 메뉴를
     * 늘어놓고 누를 때마다 403을 띄우는 것은 화면이 아니다.
     */
    @GetMapping("/admin/me")
    public Map<String, Object> adminMe() {
        AuthenticatedPrincipal principal = AuthContext.current();
        if (principal.scope() != AuthScope.ADMIN) {
            throw unauthorized();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", principal.id());
        response.put("scope", principal.scope());
        response.put("name", principal.name());
        response.put("roleName", adminRoleGuard.currentRole());
        return response;
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
                        select id, name, password, status
                        from users
                        where deleted_at is null
                          and password is not null
                          and password <> ''
                          and (student_id = ? or kingo_id = ? or email = ?)
                        order by id
                        limit 1
                        """,
                (resultSet, rowNum) -> new MemberCredential(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("password"),
                        resultSet.getString("status")
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
        List<Long> equivalentMajorIds = equivalentMajorIds(request.majorName());
        return jdbcTemplate.query("""
                        select u.id, u.name, u.phone, u.email, u.status, u.kingo_id, u.password
                        from users u
                        where u.name = ?
                          and u.admission_year = ?
                          and u.major_id in (%s)
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
                        """.formatted(placeholders(equivalentMajorIds.size())),
                (resultSet, rowNum) -> new SignupCandidate(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("phone"),
                        resultSet.getString("email"),
                        resultSet.getString("status"),
                        resultSet.getString("kingo_id"),
                        resultSet.getString("password")
                ),
                arguments(request.name().trim(), request.admissionYear(),
                        equivalentMajorIds, normalizePhone(request.phone())))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "일치하는 동문 정보를 찾을 수 없습니다."));
    }

    /**
     * 고른 학과와 같은 학과로 볼 학과 id 들.
     *
     * <p>이름이 바뀐 학과(산업공학과 ↔ 시스템경영공학과)와 야간 표기((야)법학과 ↔ 법학과)를
     * 같은 학과로 묶는다. 화면이 보여주는 이름과 같은 규칙({@link MajorNames#canonicalKey})으로
     * 판단해야 한다 — 목록에 '법학과'로 보이는데 그 이름으로 본인이 안 찾아지면 안 된다.
     *
     * <p>학과 수가 수백 개 수준이라 전부 읽어 자바에서 묶어도 부담이 없다.
     */
    private List<Long> equivalentMajorIds(String majorName) {
        String wanted = MajorNames.canonicalKey(majorName == null ? "" : majorName);
        if (wanted.isEmpty()) {
            return List.of(NO_MAJOR);
        }
        List<Map<String, Object>> majors = jdbcTemplate.queryForList(
                "select id, name, normalized_name, display_major_id from majors");
        Map<Long, String> nameById = new java.util.HashMap<>();
        majors.forEach(major -> nameById.put(number(major.get("id")), text(major.get("name"))));

        // 고른 이름이 옛 이름일 수 있다. 먼저 그 이름에 해당하는 학과의 대표 이름까지
        // 찾고 싶은 이름에 넣는다 — '법률학과'를 골랐으면 '법학과'도 같은 학과다.
        java.util.Set<String> wantedKeys = new java.util.HashSet<>();
        wantedKeys.add(wanted);
        for (Map<String, Object> major : majors) {
            if (wanted.equals(MajorNames.canonicalKey(text(major.get("name"))))
                    || wanted.equals(MajorNames.canonicalKey(text(major.get("normalized_name"))))) {
                wantedKeys.add(MajorNames.canonicalKey(shownName(major, nameById)));
            }
        }

        List<Long> matched = new java.util.ArrayList<>();
        for (Map<String, Object> major : majors) {
            if (wantedKeys.contains(MajorNames.canonicalKey(shownName(major, nameById)))
                    || wantedKeys.contains(MajorNames.canonicalKey(text(major.get("name"))))) {
                matched.add(number(major.get("id")));
            }
        }
        // 빈 목록이면 in () 가 문법 오류다. 어디에도 없는 id 를 넣어 아무도 찾지 않게 한다.
        return matched.isEmpty() ? List.of(NO_MAJOR) : matched;
    }

    /** 그 학과가 화면에 내보이는 이름. 이어받은 학과가 있으면 그 이름을 쓴다. */
    private String shownName(Map<String, Object> major, Map<Long, String> nameById) {
        Long displayMajorId = number(major.get("display_major_id"));
        String own = text(major.get("name"));
        return displayMajorId == null ? own : nameById.getOrDefault(displayMajorId, own);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private Object[] arguments(String name, Integer admissionYear, List<Long> majorIds, String phone) {
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(name);
        arguments.add(admissionYear);
        arguments.addAll(majorIds);
        arguments.add(phone);
        return arguments.toArray();
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

    /**
     * 비밀번호가 맞는데도 못 들어가는 경우에는 그 이유를 알려준다.
     *
     * <p>승인 대기 중인 사람에게 "아이디 또는 비밀번호를 확인해주세요" 만 돌려주면
     * 방금 계정을 만든 본인은 비밀번호를 잘못 적은 줄 알고 계속 다시 넣는다.
     * 비밀번호가 맞았을 때만 알려주므로 계정 존재 여부가 새어 나가지는 않는다.
     */
    private void requireLoginableStatus(String status) {
        if ("ACTIVE".equals(status)) {
            return;
        }
        String message = switch (status == null ? "" : status) {
            case "PENDING" -> "가입 승인 대기 중입니다. 회비 납부가 확인되면 관리자가 계정을 활성화합니다.";
            case "REJECTED" -> "가입이 반려된 계정입니다. 총동창회 사무처(02-741-4171)로 문의해주세요.";
            case "WITHDRAWN" -> "탈퇴 처리된 계정입니다. 다시 이용하시려면 임원 등록을 새로 신청해주세요.";
            default -> "현재 로그인할 수 없는 계정입니다. 총동창회 사무처(02-741-4171)로 문의해주세요.";
        };
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
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
            String password,
            String status
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
