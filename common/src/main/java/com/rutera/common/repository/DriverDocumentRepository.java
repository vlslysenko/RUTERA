package com.rutera.common.repository;

import com.rutera.common.domain.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverDocumentRepository extends JpaRepository<DriverDocument, Long> {

    List<DriverDocument> findByDriverId(Long driverId);

    /** Documents with a deadline of the given drivers, nearest deadline first (for the driver list). */
    List<DriverDocument> findByDriverIdInAndValidToNotNullOrderByValidToAscIdAsc(Collection<Long> driverIds);

    Optional<DriverDocument> findByIdAndDriverId(Long id, Long driverId);
}
