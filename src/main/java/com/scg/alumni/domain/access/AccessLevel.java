package com.scg.alumni.domain.access;

public enum AccessLevel {
    NONE, LIMITED, FULL;

    public java.util.Set<Permission> permissions() {
        return switch (this) {
            case NONE -> java.util.Set.of();
            case LIMITED -> java.util.Set.of(Permission.ID_CARD, Permission.READ_NOTICE, Permission.MANAGE_CREDENTIALS, Permission.MANAGE_OWN_PROFILE);
            case FULL -> java.util.Set.of(Permission.values());
        };
    }
}
