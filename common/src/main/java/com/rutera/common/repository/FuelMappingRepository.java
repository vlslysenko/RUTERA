package com.rutera.common.repository;

import com.rutera.common.domain.FuelMapping;
import com.rutera.common.domain.FuelProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface FuelMappingRepository extends JpaRepository<FuelMapping, Long> {

    Optional<FuelMapping> findByProvider(FuelProvider provider);
}
