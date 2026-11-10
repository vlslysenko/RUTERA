package com.rutera.core.calc;

import com.rutera.common.domain.FuelTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A source of fuel prices, as in the provider contract of the fuel research (2026-11-10). A NATIONAL provider (the EU
 * Weekly Oil Bulletin, national statistics, official maximum prices) is asked by {@link FuelPriceImporter} on a
 * schedule and its quotes go into {@code fuel_price_ref}, so the calculator never waits for it. A STATION provider
 * (prices of filling stations near a point) answers on demand; none is built in yet.
 */
public interface FuelPriceProvider {

    enum Kind {
        NATIONAL, STATION
    }

    /** Where a resolved price was found, in the order the calculator tries them. */
    enum Quality {
        /** Typed into the calculation. */
        MANUAL,
        /** The firma's contract price or card discount (to come). */
        CONTRACT,
        /** The firma's own fuel card purchases in that country. */
        OWN_TX,
        /** A reference price times the firma's discount factor (to come). */
        REFERENCE_ADJUSTED,
        /** A reference price of the country ({@code fuel_price_ref}). */
        REFERENCE,
        /** The firma's own purchases in all countries, in its base currency: a last resort. */
        OWN_TX_ALL,
        /** No price. */
        MISSING
    }

    /**
     * A price per litre (kg for LNG/CNG). From a provider: one country, product and day in its currency, with net
     * and gross, the VAT rate (percent) and the excise per 1000 l in the same currency where known; quality
     * REFERENCE, the base currency fields empty. Resolved for a firma: also the price in its base currency when the
     * currency is the same (fxRate 1), and the quality.
     */
    record PriceQuote(String country, FuelTransaction.Product product, BigDecimal net, BigDecimal gross,
                      BigDecimal vatRate, BigDecimal excisePer1000l, String currency, BigDecimal netBase,
                      String baseCurrency, BigDecimal fxRate, LocalDate priceDate, String source, Quality quality,
                      BigDecimal firmFactor, String note) {
    }

    /** A filling station's price near a point (STATION providers). */
    record StationPrice(String name, String brand, double lat, double lng, double distanceKm, BigDecimal price,
                        String currency, java.time.Instant at, Boolean hgv) {
    }

    /** Short name, stored as the source of its rows (e.g. EU_WOB). */
    String name();

    /** ISO country codes it has prices for. */
    Set<String> countries();

    Kind kind();

    /** NATIONAL: the quotes published since the day (rows for fuel_price_ref); an empty list when nothing is new. */
    List<PriceQuote> fetch(LocalDate since) throws Exception;

    /** STATION only: station prices around a point. */
    default List<StationPrice> near(double lat, double lng, double radiusKm, FuelTransaction.Product product)
            throws Exception {
        throw new UnsupportedOperationException(name() + " has no station prices");
    }
}
