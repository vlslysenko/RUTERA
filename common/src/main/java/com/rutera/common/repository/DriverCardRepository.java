package com.rutera.common.repository;

import com.rutera.common.domain.DriverCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Tenant-filtered: results always belong to the firma of the current request context. */
public interface DriverCardRepository extends JpaRepository<DriverCard, Long> {

    List<DriverCard> findByDriverIdOrderByIdDesc(Long driverId);

    List<DriverCard> findByDriverIdIn(Collection<Long> driverIds);

    Optional<DriverCard> findByCardNumber(String cardNumber);

    /** Cards of the same driver identification (the first 14 characters; replacement and renewal differ). */
    List<DriverCard> findByCardNumberStartingWith(String driverIdentification);
}
