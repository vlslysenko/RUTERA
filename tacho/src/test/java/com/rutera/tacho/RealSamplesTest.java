package com.rutera.tacho;

import com.rutera.tacho.card.CardActivityDay;
import com.rutera.tacho.card.DriverCardFile;
import com.rutera.tacho.model.ActivityPeriod;
import com.rutera.tacho.model.Slot;
import com.rutera.tacho.vu.VuActivityDay;
import com.rutera.tacho.vu.VuFile;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parses every file of the directory given by the system property {@code rutera.tacho.samples} (set by the module
 * pom to {@code ../../tacho-samples}, next to the clone and outside the working tree). Real files hold personal
 * data and are never committed; without the directory the test is skipped. Hidden files are left out. Only
 * counts and dates are printed.
 */
class RealSamplesTest {

    @TestFactory
    Stream<DynamicTest> everyRealSampleParsesCleanly() throws IOException {
        Path dir = Path.of(System.getProperty("rutera.tacho.samples", "../../tacho-samples"));
        assumeTrue(Files.isDirectory(dir), "No real samples in " + dir.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .sorted().toList();
        }
        assumeTrue(!files.isEmpty(), "No real samples in " + dir.toAbsolutePath());
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> check(f)));
    }

    private static void check(Path file) throws IOException {
        TachoFile parsed = TachoParser.parse(Files.readAllBytes(file));
        TachoFileSummary s = parsed.summary();
        System.out.printf("%s: %s %s, period %s..%s, downloaded %s, warnings %d%n", file.getFileName(),
                parsed.kind(), parsed.generation(), s.periodFrom(), s.periodTo(), s.downloadedAt(),
                parsed.warnings().size());
        parsed.warnings().forEach(w -> System.out.println("  warning: " + w));

        assertThat(parsed.warnings()).isEmpty();
        assertThat(s.downloadedAt()).isNotNull();
        if (parsed instanceof DriverCardFile card) {
            assertThat(s.cardNumber()).hasSize(16);
            assertThat(card.identification().expiryDate()).isAfter(card.identification().validityBegin());
            List<CardActivityDay> days = card.activityDays();
            assertSortedUnique(days.stream().map(CardActivityDay::date).toList());
            for (CardActivityDay day : days) {
                assertWithinDay(day.date(), day.periods());
            }
            System.out.printf("  days %d, vehicles %d, places %d, events %d, faults %d%n", days.size(),
                    card.vehiclesUsed().size(), card.places().size(), card.events().size(), card.faults().size());
        } else if (parsed instanceof VuFile vu) {
            assertThat(vu.overview()).isNotNull();
            assertThat(s.vin()).hasSize(17);
            List<VuActivityDay> days = vu.activityDays();
            assertSortedUnique(days.stream().map(VuActivityDay::date).toList());
            for (VuActivityDay day : days) {
                assertWithinDay(day.date(), day.periods());
            }
            System.out.printf("  days %d, card insertions %d, events %d, faults %d, calibrations %d%n", days.size(),
                    days.stream().mapToInt(d -> d.cardInsertions().size()).sum(),
                    vu.eventsAndFaults() == null ? 0 : vu.eventsAndFaults().events().size(),
                    vu.eventsAndFaults() == null ? 0 : vu.eventsAndFaults().faults().size(),
                    vu.technicalData() == null ? 0 : vu.technicalData().calibrations().size());
        }
    }

    private static void assertSortedUnique(List<LocalDate> dates) {
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).isAfter(dates.get(i - 1));
        }
    }

    /** Per slot the periods lie inside the day, do not overlap and add up to at most 24 hours. */
    private static void assertWithinDay(LocalDate day, List<ActivityPeriod> periods) {
        for (Slot slot : Slot.values()) {
            List<ActivityPeriod> ofSlot = periods.stream().filter(p -> p.slot() == slot).toList();
            long total = 0;
            for (int i = 0; i < ofSlot.size(); i++) {
                ActivityPeriod p = ofSlot.get(i);
                assertThat(LocalDate.ofInstant(p.start(), java.time.ZoneOffset.UTC)).isEqualTo(day);
                assertThat(p.end()).isAfter(p.start());
                if (i > 0) {
                    assertThat(p.start()).isAfterOrEqualTo(ofSlot.get(i - 1).end());
                }
                total += p.minutes();
            }
            assertThat(total).isLessThanOrEqualTo(1440);
        }
    }
}
