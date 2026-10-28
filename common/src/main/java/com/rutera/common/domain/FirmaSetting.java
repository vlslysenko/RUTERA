package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Firma setting: key to JSON value (reminder days, default per-diem rates, invoice counter, ...). */
@Entity
@Table(name = "firma_setting")
public class FirmaSetting extends TenantEntity {

    @Column(name = "setting_key", nullable = false, length = 100)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value", nullable = false)
    private String value;

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
}
