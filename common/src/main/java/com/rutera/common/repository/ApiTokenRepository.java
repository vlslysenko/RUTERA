package com.rutera.common.repository;

import com.rutera.common.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-filtered: results always belong to the firma of the current request context. The token lookup of the
 * public API runs in root scope (the firma is not known before the token).
 */
public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findByUserIdOrderByIdDesc(Long userId);

    long countByUserIdAndKindAndRevokedAtIsNull(Long userId, ApiToken.Kind kind);

    Optional<ApiToken> findByRefreshHash(String refreshHash);

    List<ApiToken> findByUserIdAndOauthClientIdAndRevokedAtIsNull(Long userId, Long oauthClientId);

    List<ApiToken> findAllByOrderByIdDesc();
}
