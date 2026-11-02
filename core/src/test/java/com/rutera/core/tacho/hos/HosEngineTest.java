package com.rutera.core.tacho.hos;

import com.rutera.common.domain.DriverViolation.Rule;
import com.rutera.common.domain.DriverViolation.Severity;
import com.rutera.core.tacho.hos.HosEngine.Act;
import com.rutera.core.tacho.hos.HosEngine.Seg;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules of 561/2006 on synthetic timelines (no Spring, no database). Monday 2026-03-02 starts the week. */
class HosEngineTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

    /** A day plan: a sequence of (activity, minutes) starting at {@code start}; the rest of the time is unrecorded. */
    private static final class Plan {
        final List<Seg> segs = new ArrayList<>();
        Instant cursor;

        Plan at(LocalDate day, int hour) {
            cursor = LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
            return this;
        }

        Plan then(Act act, int minutes) {
            Instant end = cursor.plusSeconds(minutes * 60L);
            segs.add(new Seg(cursor, end, act, 1, "PO 1001"));
            cursor = end;
            return this;
        }

        Plan drive(int minutes) {
            return then(Act.DRIVING, minutes);
        }

        Plan rest(int minutes) {
            return then(Act.REST, minutes);
        }

        Plan work(int minutes) {
            return then(Act.WORK, minutes);
        }

        /** A normal day from 06:00: 4 h 30 driving, 45 min break, 4 h driving, 30 min work. */
        Plan normalDay(LocalDate day) {
            return at(day, 6).drive(270).rest(45).drive(240).work(30);
        }

        HosEngine run() {
            return HosEngine.run(segs, UTC);
        }
    }

    private static List<Rule> rules(HosEngine engine) {
        return engine.violations().stream().map(HosEngine.Found::rule).toList();
    }

    @Test
    void aRegularWeekHasNoViolations() {
        Plan plan = new Plan();
        for (int i = 0; i < 5; i++) {
            plan.normalDay(MONDAY.plusDays(i));
        }
        HosEngine engine = plan.run();
        assertThat(engine.violations()).isEmpty();
        HosEngine.Day tuesday = engine.days(MONDAY.plusDays(1), MONDAY.plusDays(1)).getFirst();
        assertThat(tuesday.drivingMin()).isEqualTo(510);
        assertThat(tuesday.workMin()).isEqualTo(30);
        assertThat(tuesday.weeklyDrivingMin()).isEqualTo(1020);
        assertThat(tuesday.dailyRestMin()).isNotNull().isGreaterThanOrEqualTo(660);
        assertThat(tuesday.extDrivingInWeek()).isZero();
        assertThat(tuesday.remainingWeeklyMin()).isEqualTo(56 * 60 - 1020);
    }

    @Test
    void dailyDrivingUsesTwoExtensionsThenViolates() {
        Plan plan = new Plan();
        for (int i = 0; i < 3; i++) {
            // 9 h 30 of driving with proper breaks.
            plan.at(MONDAY.plusDays(i), 5).drive(270).rest(45).drive(270).rest(45).drive(30);
        }
        HosEngine engine = plan.run();
        assertThat(rules(engine)).containsExactly(Rule.DAILY_DRIVING_9H);
        HosEngine.Found f = engine.violations().getFirst();
        assertThat(f.actual()).isEqualTo(570);
        assertThat(f.limit()).isEqualTo(540);
        assertThat(f.severity()).isEqualTo(Severity.MINOR);
        assertThat(f.plate()).isEqualTo("PO 1001");
        assertThat(engine.days(MONDAY.plusDays(1), MONDAY.plusDays(1)).getFirst().extDrivingInWeek()).isEqualTo(2);
    }

    @Test
    void elevenHoursOfDrivingBreaksTheExtendedLimit() {
        Plan plan = new Plan().at(MONDAY, 4).drive(270).rest(45).drive(270).rest(45).drive(120);
        HosEngine engine = plan.run();
        assertThat(rules(engine)).containsExactly(Rule.DAILY_DRIVING_10H);
        assertThat(engine.violations().getFirst().actual()).isEqualTo(660);
        assertThat(engine.violations().getFirst().severity()).isEqualTo(Severity.SERIOUS);
    }

    @Test
    void drivingTooLongWithoutABreak() {
        HosEngine engine = new Plan().at(MONDAY, 6).drive(200).rest(20).drive(90).rest(45).drive(60).run();
        assertThat(rules(engine)).containsExactly(Rule.BREAK_45_AFTER_4H30);
        assertThat(engine.violations().getFirst().actual()).isEqualTo(290);
        assertThat(engine.violations().getFirst().severity()).isEqualTo(Severity.MINOR);

        // 15 min first, 30 min later is a valid split break.
        assertThat(new Plan().at(MONDAY, 6).drive(120).rest(15).drive(150).rest(30).drive(120).run().violations())
                .isEmpty();
    }

    @Test
    void shortDailyRestIsReducedThenAViolation() {
        // A known start (12 h rest), day 1 ends 15:45, 9 h unrecorded rest (reduced, allowed), day 2 from 00:45
        // with an 8 h rest only, the next rest of 11 h starts 18:00: within 24 h the longest rest is 8 h.
        Plan plan = new Plan().at(MONDAY.minusDays(1), 18).rest(12 * 60);
        plan.drive(270).rest(45).drive(240).work(30);
        plan.cursor = plan.cursor.plusSeconds(9 * 3600L);
        plan.drive(270).rest(45).drive(180);
        plan.cursor = plan.cursor.plusSeconds(8 * 3600L);
        plan.drive(60).rest(11 * 60).drive(60);
        HosEngine engine = plan.run();
        assertThat(rules(engine)).containsExactly(Rule.DAILY_REST_9H);
        HosEngine.Found f = engine.violations().getFirst();
        assertThat(f.actual()).isEqualTo(480);
        assertThat(f.limit()).isEqualTo(540);
        assertThat(f.severity()).isEqualTo(Severity.MINOR);
        assertThat(engine.days(MONDAY, MONDAY).getFirst().reducedRestInWeek()).isEqualTo(1);
    }

    @Test
    void moreThan24HoursWithoutA9HourRestIsAViolationEvenWithAnUnknownStart() {
        // The data begins with a 6 h rest (start unknown), then 25 h 30 with no rest longer than 6 h.
        Plan plan = new Plan().at(MONDAY, 0).rest(360).drive(270).rest(45).drive(240).work(60);
        plan.rest(360).drive(270).rest(45).drive(240);
        HosEngine engine = plan.run();
        assertThat(rules(engine)).contains(Rule.DAILY_REST_9H);
        HosEngine.Found rest = engine.violations().stream().filter(f -> f.rule() == Rule.DAILY_REST_9H).findFirst()
                .orElseThrow();
        assertThat(rest.actual()).isLessThan(540);
    }

    @Test
    void weeklyDrivingAndWeeklyRest() {
        Plan plan = new Plan();
        // Seven days of 9 h driving without a weekly rest: 63 h in the week, no 24 h rest within 144 h.
        for (int i = 0; i < 7; i++) {
            plan.at(MONDAY.plusDays(i), 6).drive(270).rest(45).drive(270);
        }
        HosEngine engine = plan.run();
        assertThat(rules(engine)).contains(Rule.WEEKLY_DRIVING_56H);
        HosEngine.Found weekly = engine.violations().stream().filter(f -> f.rule() == Rule.WEEKLY_DRIVING_56H)
                .findFirst().orElseThrow();
        assertThat(weekly.actual()).isEqualTo(63 * 60);
        assertThat(weekly.severity()).isEqualTo(Severity.SERIOUS);

        // A full weekly rest, then six days of work and no further weekly rest: due after 144 h.
        Plan rest = new Plan().at(MONDAY, 6).drive(240).rest(48 * 60);
        Instant afterRest = rest.cursor;
        for (int i = 0; i < 7; i++) {
            rest.cursor = afterRest.plusSeconds(i * 86_400L);
            rest.drive(240).rest(45).drive(240);
        }
        HosEngine second = rest.run();
        assertThat(rules(second)).contains(Rule.WEEKLY_REST_24H);
    }

    @Test
    void twoReducedWeeklyRestsInARow() {
        Plan plan = new Plan().at(MONDAY, 6).drive(240);
        plan.rest(30 * 60).drive(240).rest(45).drive(240);
        plan.rest(26 * 60).drive(120);
        HosEngine engine = plan.run();
        assertThat(rules(engine)).contains(Rule.WEEKLY_REST_45H);
        HosEngine.Found f = engine.violations().stream().filter(v -> v.rule() == Rule.WEEKLY_REST_45H).findFirst()
                .orElseThrow();
        assertThat(f.actual()).isEqualTo(26 * 60);
        assertThat(f.severity()).isEqualTo(Severity.VERY_SERIOUS);
    }

    @Test
    void daysAreCutInTheFirmaTimeZoneAndUnknownCountsAsRest() {
        // 22:00 to 02:00 UTC driving = 23:00 to 03:00 in Warsaw (winter time, UTC+1).
        Plan plan = new Plan().at(MONDAY, 22).drive(240).then(Act.UNKNOWN, 600).drive(60);
        HosEngine engine = HosEngine.run(plan.segs, ZoneId.of("Europe/Warsaw"));
        List<HosEngine.Day> days = engine.days(MONDAY, MONDAY.plusDays(1));
        assertThat(days.get(0).drivingMin()).isEqualTo(60);
        assertThat(days.get(1).drivingMin()).isEqualTo(240);
        assertThat(days.get(1).restMin()).isEqualTo(600);
        assertThat(days.get(1).dailyRestMin()).isEqualTo(600);
        assertThat(engine.violations()).isEmpty();
    }
}
