package com.rutera.common.repository;

import com.rutera.common.domain.DriverPayRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverPayRateRepository extends JpaRepository<DriverPayRate, Long> {

    List<DriverPayRate> findByDriverIdOrderByValidFromDesc(Long driverId);

    Optional<DriverPayRate> findByDriverIdAndValidFrom(Long driverId, LocalDate validFrom);
}
