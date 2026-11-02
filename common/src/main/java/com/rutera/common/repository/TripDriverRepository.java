package com.rutera.common.repository;

import com.rutera.common.domain.TripDriver;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TripDriverRepository extends JpaRepository<TripDriver, Long> {

    List<TripDriver> findByTripId(Long tripId);

    List<TripDriver> findByTripIdIn(Collection<Long> tripIds);
}
