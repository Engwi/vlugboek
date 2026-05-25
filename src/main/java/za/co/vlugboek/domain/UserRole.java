package za.co.vlugboek.domain;

public enum UserRole {
    USER,
    FEDERATION_ADMIN,
    SYSTEM_ADMIN,
    ADMIN;

    public boolean isSystemAdmin() {
        return this == SYSTEM_ADMIN || this == ADMIN;
    }

    public boolean isFederationAdmin() {
        return this == FEDERATION_ADMIN;
    }

    public boolean isAdminRole() {
        return isSystemAdmin() || isFederationAdmin();
    }

    public String responseName() {
        return this == ADMIN ? SYSTEM_ADMIN.name() : name();
    }
}
