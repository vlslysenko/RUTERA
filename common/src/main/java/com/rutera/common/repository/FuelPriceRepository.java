package com.rutera.common.repository;

import com.rutera.common.domain.FuelPrice;
import com.rutera.common.domain.FuelTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Not tenant-filtered: fuel prices are shared by all firmas. */
public interface FuelPriceRepository extends JpaRepository<FuelPrice, Long> {

    Optional<FuelPrice> findFirstByCountryAndProductAndValidOnLessThanEqualOrderByValidOnDesc(
            String country, FuelTransaction.Product product, LocalDate day);

    Optional<FuelPrice> findByCountryAndProductAndValidOn(String country, FuelTransaction.Product product,
                                                          LocalDate validOn);

    List<FuelPrice> findAllByOrderByCountryAscProductAscValidOnDesc();
}
