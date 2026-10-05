package com.empmg.platform.security;

/** The authenticated user carried in the security context. */
public record AuthPrincipal(
        Long userId,
        Long companyId,
        String email,
        AccessRole role,
        Long employeeId) {

    public boolean canSeeAllSalaries() {
        return role.canSeeAllSalaries();
    }
}
