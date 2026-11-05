package com.rutera.common.repository;

import com.rutera.common.domain.VehicleDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface VehicleDocumentRepository extends JpaRepository<VehicleDocument, Long> {

    List<VehicleDocument> findByVehicleId(Long vehicleId);

    List<VehicleDocument> findByValidToBetween(LocalDate from, LocalDate to);

    /** Documents with a deadline of the given vehicles, nearest deadline first (for the fleet list). */
    List<VehicleDocument> findByVehicleIdInAndValidToNotNullOrderByValidToAscIdAsc(Collection<Long> vehicleIds);

    Optional<VehicleDocument> findByIdAndVehicleId(Long id, Long vehicleId);
}
