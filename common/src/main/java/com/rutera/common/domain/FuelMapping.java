package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The column mapping a firma confirmed for a provider: JSON {field: header} and options {dateFormat,
 * decimalComma, currency}.
 */
@Entity
@Table(name = "fuel_mapping")
public class FuelMapping extends TenantEntity {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", nullable = false, length = 16)
    private FuelProvider provider;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "columns_json", nullable = false)
    private String columnsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options_json")
    private String optionsJson;

    public FuelProvider getProvider() {
        return provider;
    }

    public void setProvider(FuelProvider provider) {
        this.provider = provider;
    }

    public String getColumnsJson() {
        return columnsJson;
    }

    public void setColumnsJson(String columnsJson) {
        this.columnsJson = columnsJson;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(String optionsJson) {
        this.optionsJson = optionsJson;
    }
}
