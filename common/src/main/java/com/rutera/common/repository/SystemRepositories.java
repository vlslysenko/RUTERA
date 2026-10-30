package com.rutera.common.repository;

import com.rutera.common.domain.ActivationToken;
import com.rutera.common.domain.ApiToken;
import com.rutera.common.domain.AppSetting;
import com.rutera.common.domain.EmailOutbox;
import com.rutera.common.domain.FirmaSetting;
import com.rutera.common.domain.Notification;
import com.rutera.common.domain.RememberMeToken;
import com.rutera.common.domain.TotpRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Small repositories of group 1 tokens and group 8 system tables, kept together until they grow. */
public final class SystemRepositories {

    private SystemRepositories() {
    }

    public interface RememberMeTokenRepository extends JpaRepository<RememberMeToken, Long> {
        Optional<RememberMeToken> findBySeries(String series);

        void deleteByUserId(Long userId);
    }

    public interface ActivationTokenRepository extends JpaRepository<ActivationToken, Long> {
        Optional<ActivationToken> findByTokenHash(String tokenHash);
    }

    public interface TotpRecoveryCodeRepository extends JpaRepository<TotpRecoveryCode, Long> {
        List<TotpRecoveryCode> findByUserIdAndUsedAtIsNull(Long userId);
    }

    public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
        Optional<ApiToken> findByTokenHash(String tokenHash);

        List<ApiToken> findByUserIdAndRevokedAtIsNull(Long userId);
    }

    public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {
        List<EmailOutbox> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                EmailOutbox.Status status, Instant now);
    }

    public interface NotificationRepository extends JpaRepository<Notification, Long> {
        List<Notification> findTop100ByUserIdAndReadAtIsNullOrderByCreatedAtDesc(Long userId);
    }

    public interface FirmaSettingRepository extends JpaRepository<FirmaSetting, Long> {
        Optional<FirmaSetting> findByKey(String key);
    }

    public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
    }
}
