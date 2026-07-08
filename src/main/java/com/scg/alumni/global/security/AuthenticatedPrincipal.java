package com.scg.alumni.global.security;

public record AuthenticatedPrincipal(
        Long id,
        AuthScope scope,
        String name
) {
    public String roleName() {
        return "ROLE_" + scope.name();
    }
}
