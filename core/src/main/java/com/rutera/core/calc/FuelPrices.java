package com.rutera.core.calc;

import com.rutera.common.domain.FuelPrice;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.repository.FuelPriceRepository;
import com.rutera.common.repository.FuelTransactionRepository;
import com.rutera.core.calc.FuelPriceProvider.PriceQuote;
import com.rutera.core.calc.FuelPriceProvider.Quality;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Net fuel prices per litre for the trip calculator ({@code resolve}): in a country the firma's own fuel card
 * purchases there since the given instant (the currency with the most litres; the net of a purchase is its net,
 * else gross minus VAT, else gross without the country's VAT rate), else the reference price of the country
 * ({@code fuel_price_ref}, the newest on or before the day, a national average before a cap). Over all countries the
 * firma's average in its base currency, the last resort. A quote tells its {@link Quality}.
 */
@Service
public class FuelPrices {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final FuelTransactionRepository transactions;
    private final FuelPriceRepository table;

    public FuelPrices(FuelTransactionRepository transactions, FuelPriceRepository table) {
        this.transactions = transactions;
        this.table = table;
    }

    /** The price in a country: own fuel card purchases there, else the reference price. */
    public Optional<PriceQuote> resolve(String country, FuelTransaction.Product product, Instant since, LocalDate day,
                                        String base) {
        Map<String, BigDecimal[]> byCurrency = new HashMap<>();
        LocalDate last = null;
        for (FuelTransaction t : transactions.findByProductAndCountryAndAtGreaterThanEqual(product, country, since)) {
            BigDecimal net = net(t, day);
            if (net == null || t.getQuantity() == null || t.getQuantity().signum() <= 0 || t.getCurrency() == null) {
                continue;
            }
            BigDecimal[] sum = byCurrency.computeIfAbsent(t.getCurrency(), k -> new BigDecimal[]{BigDecimal.ZERO,
                    BigDecimal.ZERO});
            sum[0] = sum[0].add(net);
            sum[1] = sum[1].add(t.getQuantity());
            LocalDate at = LocalDate.ofInstant(t.getAt(), ZoneOffset.UTC);
            last = last == null || at.isAfter(last) ? at : last;
        }
        Map.Entry<String, BigDecimal[]> best = byCurrency.entrySet().stream()
                .max(Comparator.comparing(e -> e.getValue()[1])).orElse(null);
        if (best != null) {
            return Optional.of(quote(country, product, perUnit(best.getValue()), best.getKey(), base, last, "OWN_TX",
                    Quality.OWN_TX));
        }
        return reference(country, product, day).map(p -> quote(country, product, p.getNet(), p.getCurrency(), base,
                p.getPriceDate(), p.getSource(), Quality.REFERENCE));
    }

    /** The firma's average over all its purchases in the base currency. */
    public Optional<PriceQuote> firmAverage(FuelTransaction.Product product, String base, Instant since,
                                            LocalDate day) {
        BigDecimal[] sum = {BigDecimal.ZERO, BigDecimal.ZERO};
        LocalDate last = null;
        for (FuelTransaction t : transactions.findByProductAndAtGreaterThanEqual(product, since)) {
            BigDecimal net = net(t, day);
            if (net == null || !base.equals(t.getCurrency()) || t.getQuantity() == null
                    || t.getQuantity().signum() <= 0) {
                continue;
            }
            sum[0] = sum[0].add(net);
            sum[1] = sum[1].add(t.getQuantity());
            LocalDate at = LocalDate.ofInstant(t.getAt(), ZoneOffset.UTC);
            last = last == null || at.isAfter(last) ? at : last;
        }
        if (sum[1].signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(quote(null, product, perUnit(sum), base, base, last, "OWN_TX", Quality.OWN_TX_ALL));
    }

    /** The newest reference row on or before the day; of one day a national average before a cap or median. */
    Optional<FuelPrice> reference(String country, FuelTransaction.Product product, LocalDate day) {
        List<FuelPrice> rows = table.findTop10ByCountryAndProductAndPriceDateLessThanEqualOrderByPriceDateDescIdDesc(
                country, product, day);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        LocalDate newest = rows.getFirst().getPriceDate();
        return rows.stream().filter(r -> r.getPriceDate().equals(newest))
                .min(Comparator.comparingInt(r -> r.getKind() == FuelPrice.Kind.AVERAGE ? 0 : 1));
    }

    /** The net amount of a purchase: its net, else gross minus VAT, else gross without the country's VAT rate. */
    private BigDecimal net(FuelTransaction t, LocalDate day) {
        if (t.getNet() != null) {
            return t.getNet();
        }
        if (t.getGross() == null) {
            return null;
        }
        if (t.getVat() != null) {
            return t.getGross().subtract(t.getVat());
        }
        if (t.getCountry() == null) {
            return null;
        }
        BigDecimal rate = reference(t.getCountry(), FuelTransaction.Product.DIESEL, day)
                .map(FuelPrice::getVatRate).orElse(null);
        return rate == null ? null
                : t.getGross().multiply(HUNDRED).divide(HUNDRED.add(rate), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal perUnit(BigDecimal[] sum) {
        return sum[0].divide(sum[1], 4, RoundingMode.HALF_UP);
    }

    private static PriceQuote quote(String country, FuelTransaction.Product product, BigDecimal net, String currency,
                                    String base, LocalDate day, String source, Quality quality) {
        boolean same = currency.equals(base);
        return new PriceQuote(country, product, net.setScale(4, RoundingMode.HALF_UP), null, null, null, currency,
                same ? net : null, base, same ? BigDecimal.ONE : null, day, source, quality, null, null);
    }
}
