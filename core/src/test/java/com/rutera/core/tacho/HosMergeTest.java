package com.rutera.core.tacho;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The merge of a driver's timeline: card days win over vehicle unit data, the newest file wins. */
class HosMergeTest {

    private static HosService.Row row(String start, String end, String activity, long file, String downloaded,
                                      boolean card) {
        return new HosService.Row(Instant.parse(start), Instant.parse(end), activity, 1, "PO 1001", null, file,
                Instant.parse(downloaded), card);
    }

    @Test
    void aCardDayWinsAndTheNewestFileOfTheDayIsTaken() {
        List<HosService.Row> card = List.of(
                row("2026-03-02T06:00:00Z", "2026-03-02T10:00:00Z", "DRIVING", 1, "2026-03-05T00:00:00Z", true),
                row("2026-03-02T06:00:00Z", "2026-03-02T11:00:00Z", "DRIVING", 2, "2026-03-20T00:00:00Z", true));
        List<HosService.Row> vu = List.of(
                row("2026-03-02T06:00:00Z", "2026-03-02T09:00:00Z", "WORK", 3, "2026-03-30T00:00:00Z", false),
                row("2026-03-03T06:00:00Z", "2026-03-03T09:00:00Z", "DRIVING", 3, "2026-03-30T00:00:00Z", false),
                row("2026-03-03T06:00:00Z", "2026-03-03T08:00:00Z", "DRIVING", 4, "2026-03-10T00:00:00Z", false));
        List<HosService.Row> merged = HosService.merge(card, vu);
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).fileId()).as("newest card file of the day").isEqualTo(2);
        assertThat(merged.get(1).fileId()).as("unit data only where no card data, newest unit file").isEqualTo(3);
        assertThat(merged.get(1).end()).isEqualTo(Instant.parse("2026-03-03T09:00:00Z"));
    }

    @Test
    void theDriverIdentificationIgnoresReplacementAndRenewalIndexes() {
        assertThat(HosService.identification("PL12345678901200")).isEqualTo("PL123456789012");
        assertThat(HosService.identification("SHORT")).isEqualTo("SHORT");
    }
}
