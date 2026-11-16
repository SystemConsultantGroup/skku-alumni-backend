package com.scg.alumni.api.auth;

import com.scg.alumni.domain.access.AccessLevel;
import com.scg.alumni.domain.access.AccessPermissionCache;
import com.scg.alumni.domain.access.Permission;
import com.scg.alumni.global.security.AuthContext;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/access")
@RequiredArgsConstructor
public class MemberAccessController {
    private final AccessPermissionCache qualifications;

    @GetMapping
    public AccessResponse access() {
        AccessLevel level = qualifications.currentLevel(AuthContext.currentMemberId());
        return new AccessResponse(level, level.permissions());
    }

    public record AccessResponse(AccessLevel level, Set<Permission> permissions) { }
}
