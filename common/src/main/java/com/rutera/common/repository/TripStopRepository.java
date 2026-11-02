package com.rutera.common.repository;

import com.rutera.common.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TripStopRepository extends JpaRepository<TripStop, Long> {

    List<TripStop> findByTripIdOrderBySeqAsc(Long tripId);

    List<TripStop> findByTripIdInOrderBySeqAsc(Collection<Long> tripIds);
}
