package com.rutera.common.repository;

import com.rutera.common.domain.TripFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TripFileRepository extends JpaRepository<TripFile, Long> {

    List<TripFile> findByTripIdOrderByIdDesc(Long tripId);
}
