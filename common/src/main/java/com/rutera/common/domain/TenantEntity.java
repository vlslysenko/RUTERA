package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

/**
 * Base class of every table that belongs to one firma (marked F in docs/DB_SCHEMA.md).
 * <p>
 * The {@code firma_id} column is a Hibernate tenant id: queries are filtered to the firma of the current
 * {@link com.rutera.common.context.RequestContext} and inserts get the column filled in automatically.
 * Assigning a different firma than the current one makes Hibernate reject the insert. The service layer
 * still checks {@code entity.firmaId == context.firmaId} when it loads by id (second line of defence).
 */
@MappedSuperclass
public abstract class TenantEntity extends BaseEntity {

    @TenantId
    @Column(name = "firma_id", nullable = false, updatable = false)
    private Long firmaId;

    public Long getFirmaId() {
        return firmaId;
    }

    public void setFirmaId(Long firmaId) {
        this.firmaId = firmaId;
    }
}
