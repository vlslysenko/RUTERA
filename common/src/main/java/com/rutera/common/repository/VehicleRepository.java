package com.rutera.common.repository;

import com.rutera.common.domain.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByDeletedFalseOrderByPlateAsc();

    Optional<Vehicle> findByIdAndDeletedFalse(Long id);

    List<Vehicle> findByIdInAndDeletedFalse(Iterable<Long> ids);

    /** Detaches a trailer that is being deleted from the vehicles that use it by default. */
    @Modifying
    @Query("update Vehicle v set v.defaultTrailerId = null where v.defaultTrailerId = :trailerId")
    int clearDefaultTrailer(@Param("trailerId") Long trailerId);
}
