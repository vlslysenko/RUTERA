package com.rutera.core.calc;

import com.rutera.common.domain.FuelPrice;
import com.rutera.common.repository.FuelPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Asks every NATIONAL {@link FuelPriceProvider} for the quotes of the last 14 days (daily at 04:40 UTC, when
 * {@code rutera.scheduling.enabled}) and stores them in {@code fuel_price_ref}, one row per country, product, source
 * and day. None is built in yet: a provider is a bean of that type. A failing provider is logged and skipped.
 */
@Service
public class FuelPriceImporter {

    private static final int DAYS_BACK = 14;
    private static final Logger log = LoggerFactory.getLogger(FuelPriceImporter.class);

    private final List<FuelPriceProvider> providers;
    private final FuelPriceRepository prices;
    private final TransactionTemplate tx;

    public FuelPriceImporter(List<FuelPriceProvider> providers, FuelPriceRepository prices,
                             PlatformTransactionManager transactionManager) {
        this.providers = providers;
        this.prices = prices;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 40 4 * * *", zone = "UTC")
    public void daily() {
        run(LocalDate.now(ZoneOffset.UTC));
    }

    /** Imports the quotes of every national provider; the number of rows written. */
    public int run(LocalDate today) {
        int written = 0;
        for (FuelPriceProvider provider : providers) {
            if (provider.kind() != FuelPriceProvider.Kind.NATIONAL) {
                continue;
            }
            try {
                List<FuelPriceProvider.PriceQuote> quotes = provider.fetch(today.minusDays(DAYS_BACK));
                Instant now = Instant.now();
                Integer n = tx.execute(s -> {
                    int count = 0;
                    for (FuelPriceProvider.PriceQuote q : quotes) {
                        if (q.net() == null || q.net().signum() <= 0 || q.country() == null || q.priceDate() == null
                                || q.currency() == null || !FuelPriceService.PRODUCTS.contains(q.product())) {
                            continue;
                        }
                        FuelPrice p = prices.findByCountryAndProductAndSourceAndPriceDate(q.country(), q.product(),
                                provider.name(), q.priceDate()).orElseGet(FuelPrice::new);
                        p.setCountry(q.country());
                        p.setProduct(q.product());
                        p.setPriceDate(q.priceDate());
                        p.setNet(q.net().setScale(4, RoundingMode.HALF_UP));
                        p.setGross(q.gross() == null ? null : q.gross().setScale(4, RoundingMode.HALF_UP));
                        p.setVatRate(q.vatRate());
                        p.setExcisePer1000l(q.excisePer1000l());
                        p.setCurrency(q.currency());
                        p.setKind(FuelPrice.Kind.AVERAGE);
                        p.setSource(provider.name());
                        p.setFetchedAt(now);
                        prices.save(p);
                        count++;
                    }
                    return count;
                });
                written += n == null ? 0 : n;
                log.info("Fuel prices from {}: {} rows", provider.name(), n);
            } catch (Exception e) {
                log.warn("Fuel price provider {} failed: {}", provider.name(), e.toString());
            }
        }
        return written;
    }
}
