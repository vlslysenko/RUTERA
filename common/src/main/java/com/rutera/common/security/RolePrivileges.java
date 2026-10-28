package com.rutera.common.security;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.rutera.common.security.Privileges.*;

/**
 * Default privilege set per role (SMDP: the {@code getDefault...PrivilegesMap()} methods of PrivilegesService).
 * The effective set of a user = defaults of the role (or the custom role's own set) adjusted by the
 * per-user overrides in {@code user_privilege}.
 */
public final class RolePrivileges {

    private static final Map<Role, Set<String>> DEFAULTS = new EnumMap<>(Role.class);

    static {
        // Service superuser bypasses checks; no explicit keys.
        DEFAULTS.put(Role.SUPERUSER, Set.of());

        DEFAULTS.put(Role.OWNER, new LinkedHashSet<>(ALL));

        DEFAULTS.put(Role.DISPATCHER, ordered(
                FLEET_VEHICLE_VIEW, FLEET_VEHICLE_MANAGE, FLEET_DOCUMENT_MANAGE,
                MAP_POSITION_VIEW,
                DRIVER_VIEW, DRIVER_MANAGE, DRIVER_ABSENCE_MANAGE,
                TACHO_FILE_UPLOAD, TACHO_HOS_VIEW, TACHO_VIOLATION_ACK,
                TRIP_ORDER_VIEW, TRIP_ORDER_MANAGE, TRIP_TRIP_VIEW, TRIP_TRIP_MANAGE, TRIP_CONTRACTOR_MANAGE,
                TRIP_CMR_UPLOAD, TRIP_CMR_MANAGE,
                FUEL_TRANSACTION_VIEW,
                REPORT_VIEW,
                PROFILE_SELF_MANAGE));

        DEFAULTS.put(Role.LOGISTICIAN, ordered(
                FLEET_VEHICLE_VIEW,
                MAP_POSITION_VIEW,
                DRIVER_VIEW,
                TACHO_HOS_VIEW,
                TRIP_ORDER_VIEW, TRIP_ORDER_MANAGE, TRIP_TRIP_VIEW, TRIP_TRIP_MANAGE, TRIP_CONTRACTOR_MANAGE,
                TRIP_CMR_UPLOAD, TRIP_CMR_MANAGE,
                FUEL_TRANSACTION_VIEW,
                REPORT_VIEW,
                PROFILE_SELF_MANAGE));

        DEFAULTS.put(Role.ACCOUNTANT, ordered(
                FLEET_VEHICLE_VIEW,
                DRIVER_VIEW, DRIVER_PAYMENT_VIEW, DRIVER_PAYMENT_MANAGE,
                TRIP_ORDER_VIEW, TRIP_TRIP_VIEW, TRIP_CONTRACTOR_MANAGE, TRIP_CMR_MANAGE,
                FINANCE_INVOICE_VIEW, FINANCE_INVOICE_MANAGE, FINANCE_PAYMENT_MANAGE, FINANCE_REMINDER_MANAGE,
                FUEL_TRANSACTION_VIEW,
                REPORT_VIEW,
                PROFILE_SELF_MANAGE));

        DEFAULTS.put(Role.DRIVER, ordered(
                DRIVER_APP_SELF_USE,
                PROFILE_SELF_MANAGE));

        // Machine user: read access by default; write keys are granted per user.
        DEFAULTS.put(Role.API, ordered(
                FLEET_VEHICLE_VIEW, MAP_POSITION_VIEW, DRIVER_VIEW, TACHO_HOS_VIEW,
                TRIP_ORDER_VIEW, TRIP_TRIP_VIEW, FINANCE_INVOICE_VIEW, FUEL_TRANSACTION_VIEW, REPORT_VIEW));

        // Custom role: the set lives in custom_role.privileges.
        DEFAULTS.put(Role.CUSTOM, Set.of());
    }

    private RolePrivileges() {
    }

    /** Unmodifiable default set of the role (empty for SUPERUSER and CUSTOM). */
    public static Set<String> defaultsOf(Role role) {
        return Collections.unmodifiableSet(DEFAULTS.getOrDefault(role, Set.of()));
    }

    /**
     * Effective privileges: base set (role defaults, or the custom role's set for CUSTOM) with per-user
     * overrides applied. Unknown keys are dropped so that renamed keys cannot linger.
     *
     * @param role            the user's role
     * @param customRoleSet   privileges of the custom role, or {@code null} when the user has none
     * @param overrides       per-user overrides: key to allowed/forbidden
     */
    public static Set<String> effective(Role role, Set<String> customRoleSet, Map<String, Boolean> overrides) {
        Set<String> result = new LinkedHashSet<>();
        if (role == Role.CUSTOM) {
            if (customRoleSet != null) {
                result.addAll(customRoleSet);
            }
        } else {
            result.addAll(defaultsOf(role));
        }
        if (overrides != null) {
            overrides.forEach((key, allowed) -> {
                if (Boolean.TRUE.equals(allowed)) {
                    result.add(key);
                } else {
                    result.remove(key);
                }
            });
        }
        result.retainAll(ALL);
        return result;
    }

    private static Set<String> ordered(String... keys) {
        return new LinkedHashSet<>(java.util.List.of(keys));
    }
}
