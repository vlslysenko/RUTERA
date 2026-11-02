package com.rutera.common.repository;

import com.rutera.common.domain.OAuthCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * Tenant-filtered: results always belong to the firma of the current request context. The token endpoint looks the
 * code up in root scope (the firma is not known before the code).
 */
public interface OAuthCodeRepository extends JpaRepository<OAuthCode, Long> {

    Optional<OAuthCode> findByCodeHash(String codeHash);

    @Modifying
    @Query("delete from OAuthCode c where c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
