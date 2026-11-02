package com.rutera.common.repository;

import com.rutera.common.domain.OAuthClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** Global (no firma): OAuth clients from dynamic client registration. */
public interface OAuthClientRepository extends JpaRepository<OAuthClient, Long> {

    Optional<OAuthClient> findByClientId(String clientId);

    /** Registrations never used for an authorization (no code, no connection) and older than {@code before}. */
    @Modifying
    @Query("""
            delete from OAuthClient c where c.lastUsedAt is null and c.createdAt < :before
              and not exists (select 1 from OAuthCode o where o.clientId = c.id)
              and not exists (select 1 from ApiToken t where t.oauthClientId = c.id)""")
    int deleteUnusedBefore(@Param("before") Instant before);
}
