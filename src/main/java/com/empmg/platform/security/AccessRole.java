package com.empmg.platform.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/** Built-in access roles. No custom roles in v1 (see BUILD_INSTRUCTIONS §3.4). */
public enum AccessRole {
    ADMIN,
    CFO,
    HR,
    MANAGER,
    EMPLOYEE;

    /** CFO and Admin always see compensation data; HR only when the company allows it. */
    public boolean canSeeAllSalaries() {
        return this == ADMIN || this == CFO;
    }

    /** Roles allowed to propose raises (HR proposes, CFO approves; CFO can also apply directly). */
    public boolean canProposeRaise() {
        return this == ADMIN || this == CFO || this == HR;
    }

    /** Roles allowed to approve/apply raises — CFO/Finance only. */
    public boolean canApproveRaise() {
        return this == ADMIN || this == CFO;
    }

    /** Roles allowed to manage employees, tiers, job roles. */
    public boolean canManageCompanyData() {
        return this == ADMIN || this == CFO || this == HR;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + name()));
    }
}
