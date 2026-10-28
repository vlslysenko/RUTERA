package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Global runtime setting shared by all firmas. Never a secret: those live in environment variables. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 100)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value", nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
