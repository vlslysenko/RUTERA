package com.rutera.common.repository;

import com.rutera.common.domain.FuelTaxRate;
import com.rutera.common.domain.FuelTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/** Not tenant-filtered: the tax calendar is shared by all firmas. */
public interface FuelTaxRateRepository extends JpaRepository<FuelTaxRate, Long> {

    /** The period that started last on or before the day (its valid_to is checked by the caller). */
    Optional<FuelTaxRate> findFirstByCountryAndProductAndValidFromLessThanEqualOrderByValidFromDesc(
            String country, FuelTransaction.Product product, LocalDate day);
}
