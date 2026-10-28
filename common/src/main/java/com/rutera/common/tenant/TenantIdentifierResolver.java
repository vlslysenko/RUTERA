package com.rutera.common.tenant;

import com.rutera.common.context.RequestContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells Hibernate which firma the current session belongs to, so that every query on an entity with a
 * {@code @TenantId firma_id} column is filtered automatically and inserts get the column filled in.
 * <p>
 * Replaces the manual {@code idfirma = ?} conditions of SMDP (see MULTITENANT_FIRMA_NULL_LEAK.md there).
 * <ul>
 *   <li>Firma in {@link RequestContext}: that firma.</li>
 *   <li>Root scope: {@link #ROOT_TENANT}; {@link #isRoot(Long)} is true, so Hibernate applies no filter.</li>
 *   <li>No scope at all: {@link #NO_TENANT}, which matches no row. Forgetting to set the context therefore
 *       returns nothing instead of leaking every firma's data.</li>
 * </ul>
 * Registered with Hibernate through {@link HibernatePropertiesCustomizer}.
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<Long>, HibernatePropertiesCustomizer {

    /** Sentinel for "all firmas"; never a real firma id (AUTO_INCREMENT starts at 1). */
    public static final Long ROOT_TENANT = 0L;
    /** Sentinel for "no firma": matches no row. */
    public static final Long NO_TENANT = -1L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        if (RequestContext.isRoot()) {
            return ROOT_TENANT;
        }
        return RequestContext.firmaId().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return ROOT_TENANT.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
