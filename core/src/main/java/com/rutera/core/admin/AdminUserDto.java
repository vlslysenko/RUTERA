package com.rutera.core.admin;

import com.rutera.common.domain.AppUser;
import com.rutera.common.security.Role;

import java.time.Instant;

/** A user of the firma as shown on the administration screen ({@code AdminUser} in the frontend contract). */
public record AdminUserDto(
        Long id,
        String login,
        String firstName,
        String lastName,
        String name,
        Role role,
        Long customRoleId,
        String phone,
        String language,
        boolean active,
        boolean locked,
        int failedLogins,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminUserDto of(AppUser user, Instant now) {
        return new AdminUserDto(user.getId(), user.getLogin(), user.getFirstName(), user.getLastName(),
                user.getFullName().trim(), user.getRole(), user.getCustomRoleId(), user.getPhone(), user.getLang(),
                user.isActive(), user.isLockedAt(now), user.getFailedLogins(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
