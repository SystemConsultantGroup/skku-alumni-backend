package com.scg.alumni.api.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/members/{memberId}/access-block")
@RequiredArgsConstructor
public class AdminAccountAccessController {
    private final JdbcTemplate jdbc;
    private final AdminAuditLog audit;
    private final com.scg.alumni.domain.access.AccessCacheInvalidation invalidation;

    @PatchMapping
    @Transactional
    public Map<String, Object> update(@PathVariable long memberId, @Valid @RequestBody Request request) {
        invalidation.afterCommit(memberId);
        int changed = jdbc.update("update users set account_blocked = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted_at is null",
                request.blocked(), memberId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        audit.record(request.blocked() ? "BLOCK_MEMBER_ACCESS" : "UNBLOCK_MEMBER_ACCESS", "user", memberId);
        return Map.of("memberId", memberId, "blocked", request.blocked());
    }

    public record Request(@NotNull Boolean blocked) { }
}
