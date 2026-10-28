package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Per-user override of one privilege key (SMDP operator2privileges). */
@Entity
@Table(name = "user_privilege")
public class UserPrivilege extends TenantEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "privilege_key", nullable = false, length = 100)
    private String privilegeKey;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPrivilegeKey() {
        return privilegeKey;
    }

    public void setPrivilegeKey(String privilegeKey) {
        this.privilegeKey = privilegeKey;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }
}
