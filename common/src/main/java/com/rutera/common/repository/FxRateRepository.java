package com.rutera.common.repository;

import com.rutera.common.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/** Not tenant-filtered: exchange rates are shared by all firmas. */
public interface FxRateRepository extends JpaRepository<FxRate, Long> {

    Optional<FxRate> findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String source,
                                                                                            String currency,
                                                                                            LocalDate day);

    Optional<FxRate> findBySourceAndCurrencyAndRateDate(String source, String currency, LocalDate rateDate);
}
