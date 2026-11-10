package com.rutera.core.calc;

import com.rutera.common.domain.FuelPrice;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.repository.FuelPriceRepository;
import com.rutera.common.repository.FuelTransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Fuel prices for the trip calculator, net per litre. In a country: the firma's own fuel card purchases there since
 * the given instant (the currency with the most litres), else the shared price table ({@code fuel_price}, latest row
 * on or before the day). For the whole trip: the firma's average in its base currency.
 */
@Service
public class FuelPrices {

    /** The price given in the request's options. */
    public static final String OPTIONS = "OPTIONS";
    /** The firma's fuel card purchases in that country. */
    public static final String FUEL_CARDS = "FUEL_CARDS";
    /** The shared price table. */
    public static final String PRICE_TABLE = "PRICE_TABLE";
    /** The firma's fuel card purchases anywhere, in its base currency. */
    public static final String FUEL_CARDS_ALL = "FUEL_CARDS_ALL";

    /** A price per litre with its currency and where it comes from. */
    public record Price(BigDecimal perLitre, String currency, String source) {
    }

    private final FuelTransactionRepository transactions;
    private final FuelPriceRepository table;

    public FuelPrices(FuelTransactionRepository transactions, FuelPriceRepository table) {
        this.transactions = transactions;
        this.table = table;
    }

    /** The price in a country: the firma's fuel cards there, else the price table. */
    public Optional<Price> inCountry(String country, FuelTransaction.Product product, Instant since, LocalDate day) {
        List<Object[]> rows = transactions.averagePriceInCountry(product, country, since);
        Object[] best = null;
        for (Object[] row : rows) {
            if (row[1] != null && (best == null || ((BigDecimal) row[2]).compareTo((BigDecimal) best[2]) > 0)) {
                best = row;
            }
        }
        if (best != null) {
            return Optional.of(new Price(scale((BigDecimal) best[1]), (String) best[0], FUEL_CARDS));
        }
        return table.findFirstByCountryAndProductAndValidOnLessThanEqualOrderByValidOnDesc(country, product, day)
                .map(FuelPrices::of);
    }

    /** The firma's average over all its purchases in the base currency. */
    public Optional<Price> firmAverage(FuelTransaction.Product product, String currency, Instant since) {
        BigDecimal average = transactions.averagePrice(product, currency, since);
        return average == null ? Optional.empty() : Optional.of(new Price(scale(average), currency, FUEL_CARDS_ALL));
    }

    private static Price of(FuelPrice p) {
        return new Price(p.getPriceNet(), p.getCurrency(), PRICE_TABLE);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
