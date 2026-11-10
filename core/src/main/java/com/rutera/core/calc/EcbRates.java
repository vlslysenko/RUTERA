package com.rutera.core.calc;

import com.rutera.common.domain.FxRate;
import com.rutera.common.repository.FxRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The ECB euro foreign exchange reference rates ({@code rutera.fx.ecb.url}, the last 90 days as XML): fetched on
 * working days at 16:30 Berlin time, after the ECB publishes them, and stored in {@code fx_rate} (units per 1 EUR).
 * Off with {@code rutera.fx.ecb.enabled=false}; needs internet.
 */
@Service
public class EcbRates {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(EcbRates.class);

    /** One rate: units of the currency per 1 EUR on a day. */
    record Quote(LocalDate day, String currency, BigDecimal rate) {
    }

    private final FxRateRepository rates;
    private final TransactionTemplate tx;
    private final String url;
    private final boolean enabled;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public EcbRates(FxRateRepository rates, PlatformTransactionManager transactionManager,
                    @Value("${rutera.fx.ecb.url:https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml}")
                    String url,
                    @Value("${rutera.fx.ecb.enabled:true}") boolean enabled,
                    @Value("${rutera.geocoding.user-agent:RUTERA}") String userAgent) {
        this.rates = rates;
        this.tx = new TransactionTemplate(transactionManager);
        this.url = url;
        this.enabled = enabled;
        this.userAgent = userAgent;
    }

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Europe/Berlin")
    public void daily() {
        if (!enabled) {
            return;
        }
        try {
            int n = store(parse(download()));
            log.info("ECB rates: {} rows", n);
        } catch (Exception e) {
            log.warn("ECB rates not fetched: {}", e.toString());
        }
    }

    /** Stores the quotes, replacing the rate of the same currency and day; the number of rows written. */
    int store(List<Quote> quotes) {
        Integer n = tx.execute(s -> {
            int count = 0;
            for (Quote q : quotes) {
                FxRate r = rates.findBySourceAndCurrencyAndRateDate(Fx.SOURCE, q.currency(), q.day())
                        .orElseGet(FxRate::new);
                if (r.getId() != null && r.getRate().compareTo(q.rate()) == 0) {
                    continue;
                }
                r.setSource(Fx.SOURCE);
                r.setCurrency(q.currency());
                r.setRateDate(q.day());
                r.setRate(q.rate());
                rates.save(r);
                count++;
            }
            return count;
        });
        return n == null ? 0 : n;
    }

    /** The rates of an ECB eurofxref XML file (daily or history). */
    static List<Quote> parse(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        List<Quote> quotes = new ArrayList<>();
        LocalDate day = null;
        while (r.hasNext()) {
            if (r.next() != XMLStreamConstants.START_ELEMENT || !r.getLocalName().equals("Cube")) {
                continue;
            }
            String time = r.getAttributeValue(null, "time");
            if (time != null) {
                day = LocalDate.parse(time);
                continue;
            }
            String currency = r.getAttributeValue(null, "currency");
            String rate = r.getAttributeValue(null, "rate");
            if (day != null && currency != null && currency.matches("[A-Z]{3}") && rate != null) {
                BigDecimal value = new BigDecimal(rate);
                if (value.signum() > 0) {
                    quotes.add(new Quote(day, currency, value));
                }
            }
        }
        return quotes;
    }

    private byte[] download() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", userAgent).GET().build();
        HttpResponse<InputStream> res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) {
                throw new IOException("the ECB answered " + res.statusCode());
            }
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("the ECB file is too large");
            }
            return data;
        }
    }
}
