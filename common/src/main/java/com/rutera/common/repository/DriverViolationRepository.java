package com.rutera.common.repository;

import com.rutera.common.domain.DriverViolation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverViolationRepository extends JpaRepository<DriverViolation, Long> {

    List<DriverViolation> findByDriverId(Long driverId);

    List<DriverViolation> findByDriverIdAndStartAtBetween(Long driverId, Instant from, Instant to);

    /** Every filter is optional (null = any); {@code from}/{@code to} bound startAt. Newest first. */
    @Query("""
            select v from DriverViolation v
            where (:driverId is null or v.driverId = :driverId)
              and (:severity is null or v.severity = :severity)
              and (:unacknowledged = false or v.ackAt is null)
              and (:from is null or v.startAt >= :from)
              and (:to is null or v.startAt < :to)
            order by v.startAt desc, v.id desc""")
    Page<DriverViolation> search(@Param("driverId") Long driverId,
                                 @Param("severity") DriverViolation.Severity severity,
                                 @Param("unacknowledged") boolean unacknowledged, @Param("from") Instant from,
                                 @Param("to") Instant to, Pageable pageable);
}
