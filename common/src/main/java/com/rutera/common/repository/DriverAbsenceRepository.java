package com.rutera.common.repository;

import com.rutera.common.domain.DriverAbsence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverAbsenceRepository extends JpaRepository<DriverAbsence, Long> {

    /** Absences overlapping [from, to] (null = unbounded), optionally of one driver and kind; newest first. */
    @Query("""
            select a from DriverAbsence a
            where (:driverId is null or a.driverId = :driverId)
              and (:kind is null or a.kind = :kind)
              and (:from is null or a.toDate >= :from)
              and (:to is null or a.fromDate <= :to)
            order by a.fromDate desc, a.id desc""")
    List<DriverAbsence> search(@Param("driverId") Long driverId, @Param("kind") DriverAbsence.Kind kind,
                               @Param("from") LocalDate from, @Param("to") LocalDate to);
}
