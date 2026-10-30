package com.rutera.common.repository;

import com.rutera.common.domain.TachoFileRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface TachoFileRepository extends JpaRepository<TachoFileRecord, Long> {

    Optional<TachoFileRecord> findBySha256(String sha256);

    /**
     * The journal: every filter is optional (null = any); {@code from}/{@code to} select files whose period
     * overlaps the range. Newest uploads first.
     */
    @Query("""
            select f from TachoFileRecord f
            where (:kind is null or f.kind = :kind)
              and (:driverId is null or f.driverId = :driverId)
              and (:vehicleId is null or f.vehicleId = :vehicleId)
              and (:unassigned = false or (f.driverId is null and f.vehicleId is null))
              and (:from is null or f.periodTo >= :from)
              and (:to is null or f.periodFrom <= :to)
            order by f.uploadedAt desc, f.id desc""")
    Page<TachoFileRecord> search(@Param("kind") TachoFileRecord.Kind kind, @Param("driverId") Long driverId,
                                 @Param("vehicleId") Long vehicleId, @Param("unassigned") boolean unassigned,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    /** Newest download of one card among the files of a driver (for driver_card.last_download_at). */
    @Query("select max(f.downloadedAt) from TachoFileRecord f"
            + " where f.driverId = :driverId and f.kind = :kind and f.cardNumber = :cardNumber")
    Instant lastDownloadOfCard(@Param("driverId") Long driverId, @Param("cardNumber") String cardNumber,
                               @Param("kind") TachoFileRecord.Kind kind);

    /** Newest download among the vehicle unit files of a vehicle (for vehicle.last_vu_download_at). */
    @Query("select max(f.downloadedAt) from TachoFileRecord f where f.vehicleId = :vehicleId and f.kind = :kind")
    Instant lastDownloadOfVehicle(@Param("vehicleId") Long vehicleId, @Param("kind") TachoFileRecord.Kind kind);
}
