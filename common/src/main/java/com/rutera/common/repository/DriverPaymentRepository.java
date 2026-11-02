package com.rutera.common.repository;

import com.rutera.common.domain.DriverPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverPaymentRepository extends JpaRepository<DriverPayment, Long> {

    /** Payments of [from, to] (YYYY-MM, null = unbounded), optionally of one driver and kind; newest period first. */
    @Query("""
            select p from DriverPayment p
            where (:driverId is null or p.driverId = :driverId)
              and (:kind is null or p.kind = :kind)
              and (:from is null or p.period >= :from)
              and (:to is null or p.period <= :to)
            order by p.period desc, p.id desc""")
    List<DriverPayment> search(@Param("driverId") Long driverId, @Param("kind") DriverPayment.Kind kind,
                               @Param("from") String from, @Param("to") String to);
}
