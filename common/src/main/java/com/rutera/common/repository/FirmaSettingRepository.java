package com.rutera.common.repository;

import com.rutera.common.domain.FirmaSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Tenant-filtered: the key-value settings of the firma of the current request context. */
public interface FirmaSettingRepository extends JpaRepository<FirmaSetting, Long> {

    Optional<FirmaSetting> findByKey(String key);
}
