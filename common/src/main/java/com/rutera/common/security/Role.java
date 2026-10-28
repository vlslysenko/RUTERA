package com.rutera.common.security;

/**
 * User roles (stored as strings, never ordinals: the SMDP {@code RoleOperator} ORDINAL mapping froze its order).
 * <p>
 * {@link #level} is the hierarchy used to decide who may manage whom (SMDP "hpoziom"): a user may only
 * create or edit users whose level is greater than or equal to their own. {@link #CUSTOM} takes the level of
 * its {@code custom_role} row.
 */
public enum Role {
    /** Service superuser: no firma, bypasses privilege checks. Not available to customers. */
    SUPERUSER(0),
    OWNER(1),
    DISPATCHER(3),
    LOGISTICIAN(3),
    ACCOUNTANT(3),
    /** Machine user for the public API / MCP (paid module). */
    API(4),
    /** Privileges come from the firma's custom_role row. */
    CUSTOM(5),
    DRIVER(9);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    public boolean isSuperuser() {
        return this == SUPERUSER;
    }
}
