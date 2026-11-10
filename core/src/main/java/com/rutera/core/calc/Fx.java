package com.rutera.core.calc;

import com.rutera.common.domain.FxRate;
import com.rutera.common.repository.FxRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Currency conversion with the ECB reference rates ({@code fx_rate}, units per 1 EUR): the latest rate on or before
 * the day. EUR to X is X's rate, X to EUR its inverse, X to Y through EUR.
 */
@Service
public class Fx {

    static final String SOURCE = "ECB";
    private static final MathContext PRECISION = new MathContext(16, RoundingMode.HALF_UP);

    /** Units of {@code to} per 1 {@code from} on a day, with the day of the older of the two rates used. */
    public record Rate(String from, String to, BigDecimal rate, LocalDate rateDate, String source) {
    }

    private final FxRateRepository rates;

    public Fx(FxRateRepository rates) {
        this.rates = rates;
    }

    public Optional<Rate> rate(String from, String to, LocalDate day) {
        if (from == null || to == null) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(new Rate(from, to, BigDecimal.ONE, day, SOURCE));
        }
        Optional<FxRate> fromRate = perEuro(from, day);
        Optional<FxRate> toRate = perEuro(to, day);
        boolean fromEuro = "EUR".equals(from);
        boolean toEuro = "EUR".equals(to);
        if ((!fromEuro && fromRate.isEmpty()) || (!toEuro && toRate.isEmpty())) {
            return Optional.empty();
        }
        BigDecimal perEuroFrom = fromEuro ? BigDecimal.ONE : fromRate.get().getRate();
        BigDecimal perEuroTo = toEuro ? BigDecimal.ONE : toRate.get().getRate();
        LocalDate date = fromEuro ? toRate.get().getRateDate() : toEuro ? fromRate.get().getRateDate()
                : min(fromRate.get().getRateDate(), toRate.get().getRateDate());
        return Optional.of(new Rate(from, to, perEuroTo.divide(perEuroFrom, PRECISION), date, SOURCE));
    }

    private Optional<FxRate> perEuro(String currency, LocalDate day) {
        return "EUR".equals(currency) ? Optional.empty()
                : rates.findFirstBySourceAndCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(SOURCE, currency, day);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
