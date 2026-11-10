package com.rutera.core.calc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of the ECB eurofxref files (the daily file quotes attributes with ', the history with "). */
class EcbRatesTest {

    @Test
    void readsDaysCurrenciesAndRates() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gesmes:Envelope xmlns:gesmes=\"http://www.gesmes.org/"
                + "xml/2002-08-01\" xmlns=\"http://www.ecb.int/vocabulary/2002-08-01/eurofxref\"><Cube>"
                + "<Cube time='2026-11-10'><Cube currency='PLN' rate='4.3870'/><Cube currency='CZK' rate='24.337'/></Cube>"
                + "<Cube time=\"2026-11-02\"><Cube currency=\"PLN\" rate=\"4.4185\"/><Cube currency=\"xx\" rate=\"1\"/>"
                + "</Cube></Cube></gesmes:Envelope>";
        List<EcbRates.Quote> quotes = EcbRates.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(quotes).containsExactly(
                new EcbRates.Quote(LocalDate.parse("2026-11-10"), "PLN", new java.math.BigDecimal("4.3870")),
                new EcbRates.Quote(LocalDate.parse("2026-11-10"), "CZK", new java.math.BigDecimal("24.337")),
                new EcbRates.Quote(LocalDate.parse("2026-11-02"), "PLN", new java.math.BigDecimal("4.4185")));
    }
}
