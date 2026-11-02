package com.rutera.common.repository;

import com.rutera.common.domain.DriverDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverDayRepository extends JpaRepository<DriverDay, Long> {

    List<DriverDay> findByDriverIdAndDayBetween(Long driverId, LocalDate from, LocalDate to);

    Optional<DriverDay> findByDriverIdAndDay(Long driverId, LocalDate day);
}
