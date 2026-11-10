package com.rutera.core.calc;

import com.rutera.common.domain.FuelTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A source of fuel prices per country (a public bulletin, a price API). Every bean of this type is asked once a day
 * by {@link FuelPriceImporter}; its quotes go into the shared price table, so the calculator never waits for it and
 * keeps working when it is down.
 */
public interface FuelPriceProvider {

    /** Short name, stored as the source of its rows. */
    String name();

    /** The current prices: net per litre (kg for LNG/CNG) without VAT. An empty list when nothing is new. */
    List<Quote> quotes(LocalDate today) throws Exception;

    /** One price of one product in one country. */
    record Quote(String country, FuelTransaction.Product product, BigDecimal priceNet, String currency,
                 LocalDate validOn) {
    }
}
