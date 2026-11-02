package com.rutera.common.repository;

import com.rutera.common.domain.Trip;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TripRepository extends JpaRepository<Trip, Long> {

    /**
     * The trip list: every filter is optional (null = any). {@code from}/{@code to} select trips whose planned
     * period overlaps the range; {@code q} is a lower-case LIKE pattern matched against the trip number, the plate of
     * the vehicle or trailer and the numbers of the orders. Newest trips first.
     */
    @Query("""
            select t from Trip t
            where (:status is null or t.status = :status)
              and (:vehicleId is null or t.vehicleId = :vehicleId or t.trailerId = :vehicleId)
              and (:driverId is null
                   or exists (select d.id from TripDriver d where d.tripId = t.id and d.driverId = :driverId))
              and (:from is null or t.plannedEnd is null or t.plannedEnd >= :from)
              and (:to is null or t.plannedStart is null or t.plannedStart <= :to)
              and (:q is null or lower(t.number) like :q
                   or exists (select v.id from Vehicle v
                              where (v.id = t.vehicleId or v.id = t.trailerId) and lower(v.plate) like :q)
                   or exists (select o.id from TransportOrder o where o.tripId = t.id and lower(o.number) like :q))
            order by t.plannedStart desc nulls first, t.id desc""")
    Page<Trip> search(@Param("status") Trip.Status status, @Param("vehicleId") Long vehicleId,
                      @Param("driverId") Long driverId, @Param("from") Instant from, @Param("to") Instant to,
                      @Param("q") String q, Pageable pageable);

    Optional<Trip> findByNumber(String number);

    @Query("select t.number from Trip t where t.number like :prefix")
    List<String> numbersLike(@Param("prefix") String prefix);
}
