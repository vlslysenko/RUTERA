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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Asks every {@link FuelPriceProvider} once a day (04:40 UTC, when {@code rutera.scheduling.enabled}) and stores its
 * quotes in the shared price table, one row per country, product and day. None is built in yet: a provider is a
 * bean of that type. A failing provider is logged and skipped.
 */
@Service
public class FuelPriceImporter {

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

    /** Imports the quotes of every provider; the number of rows written. */
    public int run(LocalDate today) {
        int written = 0;
        for (FuelPriceProvider provider : providers) {
            try {
                List<FuelPriceProvider.Quote> quotes = provider.quotes(today);
                Integer n = tx.execute(s -> {
                    int count = 0;
                    for (FuelPriceProvider.Quote q : quotes) {
                        if (q.priceNet() == null || q.priceNet().signum() <= 0 || q.country() == null
                                || !FuelPriceService.PRODUCTS.contains(q.product())) {
                            continue;
                        }
                        FuelPrice p = prices.findByCountryAndProductAndValidOn(q.country(), q.product(), q.validOn())
                                .orElseGet(FuelPrice::new);
                        p.setCountry(q.country());
                        p.setProduct(q.product());
                        p.setValidOn(q.validOn());
                        p.setPriceNet(q.priceNet().setScale(4, RoundingMode.HALF_UP));
                        p.setCurrency(q.currency());
                        p.setSource(provider.name());
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
