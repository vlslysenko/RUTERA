package com.rutera.common.repository;

import com.rutera.common.domain.FuelPrice;
import com.rutera.common.domain.FuelTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Not tenant-filtered: reference fuel prices are shared by all firmas. */
public interface FuelPriceRepository extends JpaRepository<FuelPrice, Long> {

    /** The newest rows on or before the day, newest first (a few, to choose between sources of the same day). */
    List<FuelPrice> findTop10ByCountryAndProductAndPriceDateLessThanEqualOrderByPriceDateDescIdDesc(
            String country, FuelTransaction.Product product, LocalDate day);

    Optional<FuelPrice> findByCountryAndProductAndSourceAndPriceDate(String country, FuelTransaction.Product product,
                                                                     String source, LocalDate priceDate);

    List<FuelPrice> findAllByOrderByCountryAscProductAscPriceDateDescIdDesc();
}
