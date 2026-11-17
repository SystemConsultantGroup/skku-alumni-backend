package com.scg.alumni.api.operations;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessQualificationService;
import com.scg.alumni.domain.access.Permission;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/members/{memberId}/access")
@RequiredArgsConstructor
public class AdminMemberAccessController {
    private final AccessQualificationService qualifications;
    private final JdbcTemplate jdbc;

    @GetMapping
    public AccessResponse access(@PathVariable long memberId) {
        var rows = jdbc.queryForList("select account_blocked from users where id = ?", memberId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");
        Object raw = rows.getFirst().get("account_blocked");
        boolean blocked = Boolean.TRUE.equals(raw) || raw instanceof Number n && n.intValue() != 0;
        var details = qualifications.currentDetails(memberId);
        return new AccessResponse(details.level(), details.level().permissions(), blocked, details);
    }

    public record AccessResponse(AccessLevel level, Set<Permission> permissions, boolean blocked, AccessQualificationService.QualificationDetails details) { }
}
