package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Role with an individual privilege set, defined by the firma (SMDP OwnOpRole). */
@Entity
@Table(name = "custom_role")
public class CustomRole extends TenantEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Hierarchy level, see {@link com.rutera.common.security.Role#getLevel()}. */
    @Column(name = "level", nullable = false)
    private int level = 5;

    /** JSON array of privilege keys, e.g. ["fleet.vehicle/view","trip.trip/manage"]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privileges", nullable = false)
    private String privileges = "[]";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public String getPrivileges() {
        return privileges;
    }

    public void setPrivileges(String privileges) {
        this.privileges = privileges;
    }
}
