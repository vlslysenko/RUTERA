package com.rutera.common.repository;

import com.rutera.common.domain.DriverExplanation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverExplanationRepository extends JpaRepository<DriverExplanation, Long> {

    @Query("""
            select e from DriverExplanation e
            where (:status is null or e.status = :status)
              and (:driverId is null or e.driverId = :driverId)
              and (:from is null or e.startedAt >= :from)
              and (:to is null or e.startedAt < :to)
            order by e.startedAt desc, e.id desc""")
    Page<DriverExplanation> search(@Param("status") DriverExplanation.Status status, @Param("driverId") Long driverId,
                                   @Param("from") Instant from, @Param("to") Instant to, Pageable page);

    Optional<DriverExplanation> findByDriverIdAndRuleCodeAndStartedAt(Long driverId, String ruleCode,
                                                                     Instant startedAt);

    List<DriverExplanation> findByViolationIdIn(Collection<Long> violationIds);

    @Modifying
    @Query("update DriverExplanation e set e.violationId = null where e.violationId in :ids")
    int detachViolations(@Param("ids") Collection<Long> ids);
}
