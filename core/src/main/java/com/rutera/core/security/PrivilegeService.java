package com.rutera.core.security;

import com.rutera.common.context.RequestContext;
import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.CustomRole;
import com.rutera.common.domain.UserPrivilege;
import com.rutera.common.repository.CustomRoleRepository;
import com.rutera.common.repository.UserPrivilegeRepository;
import com.rutera.common.security.Role;
import com.rutera.common.security.RolePrivileges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Effective privilege set of a user: role defaults (or the custom role's set) adjusted by the per-user
 * overrides in {@code user_privilege}, see {@link RolePrivileges#effective}.
 * <p>
 * {@code custom_role} and {@code user_privilege} are tenant-filtered tables, so the lookup runs in the scope of
 * the user's firma. Call it outside a transaction (login, {@code /app/auth/me}) or from a transaction that is
 * already scoped to the same firma: Hibernate fixes the tenant when the session opens.
 */
@Service
public class PrivilegeService {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeService.class);

    private final UserPrivilegeRepository userPrivileges;
    private final CustomRoleRepository customRoles;
    private final ObjectMapper objectMapper;

    public PrivilegeService(UserPrivilegeRepository userPrivileges, CustomRoleRepository customRoles,
                            ObjectMapper objectMapper) {
        this.userPrivileges = userPrivileges;
        this.customRoles = customRoles;
        this.objectMapper = objectMapper;
    }

    /** Empty for the superuser (bypasses checks) and for a user without a firma. */
    public Set<String> effectivePrivileges(AppUser user) {
        if (user.getRole() == Role.SUPERUSER || user.getFirmaId() == null) {
            return Set.of();
        }
        try {
            return RequestContext.runAsFirma(user.getFirmaId(), user.getId(), () -> compute(user));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Privilege lookup failed for user " + user.getId(), e);
        }
    }

    private Set<String> compute(AppUser user) {
        Set<String> customSet = null;
        if (user.getRole() == Role.CUSTOM && user.getCustomRoleId() != null) {
            customSet = customRoles.findById(user.getCustomRoleId())
                    .map(this::parsePrivileges)
                    .orElseGet(() -> {
                        log.warn("User {} refers to a missing custom role {}", user.getId(), user.getCustomRoleId());
                        return Set.of();
                    });
        }
        Map<String, Boolean> overrides = new LinkedHashMap<>();
        for (UserPrivilege override : userPrivileges.findByUserId(user.getId())) {
            overrides.put(override.getPrivilegeKey(), override.isAllowed());
        }
        return RolePrivileges.effective(user.getRole(), customSet, overrides);
    }

    private Set<String> parsePrivileges(CustomRole role) {
        String json = role.getPrivileges();
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        List<String> keys = objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        return new LinkedHashSet<>(keys);
    }
}
