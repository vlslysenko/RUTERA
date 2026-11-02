package com.rutera.core.calc.plan;

import com.rutera.core.calc.plan.HosPlanner.Event;
import com.rutera.core.calc.plan.HosPlanner.Kind;
import com.rutera.core.calc.plan.HosPlanner.Leg;
import com.rutera.core.calc.plan.HosPlanner.Options;
import com.rutera.core.tacho.hos.HosEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Planning of breaks and rests under 561/2006 for one driver and for a crew of two (zone UTC). */
class HosPlannerTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Instant MONDAY_6 = Instant.parse("2026-11-02T06:00:00Z");

    private static Leg leg(double km, int driveMin, int serviceMin) {
        return new Leg(null, km, driveMin, serviceMin);
    }

    private static List<Kind> kinds(HosPlanner.Result r) {
        return r.events().stream().map(Event::kind).toList();
    }

    @Test
    void oneDriverWithExtensionAndReducedRest() {
        HosPlanner.Result r = HosPlanner.plan(MONDAY_6, List.of(leg(1000, 857, 0)),
                List.of(DriverState.fresh(MONDAY_6, UTC)), new Options(true, true, false));
        assertThat(kinds(r)).containsExactly(Kind.DRIVE, Kind.BREAK, Kind.DRIVE, Kind.BREAK, Kind.DRIVE,
                Kind.REDUCED_DAILY_REST, Kind.DRIVE);
        assertThat(r.events().get(4).end()).isEqualTo(Instant.parse("2026-11-02T17:30:00Z"));
        assertThat(r.events().get(5).minutes()).isEqualTo(540);
        assertThat(r.eta()).isEqualTo(Instant.parse("2026-11-03T06:47:00Z"));
        assertThat(r.events().getLast().kmTo()).isCloseTo(1000, within(0.01));
    }

    @Test
    void oneDriverWithoutReductions() {
        HosPlanner.Result r = HosPlanner.plan(MONDAY_6, List.of(leg(1000, 857, 0)),
                List.of(DriverState.fresh(MONDAY_6, UTC)), new Options(false, false, false));
        assertThat(kinds(r)).containsExactly(Kind.DRIVE, Kind.BREAK, Kind.DRIVE, Kind.DAILY_REST, Kind.DRIVE,
                Kind.BREAK, Kind.DRIVE);
        assertThat(r.events().get(3).minutes()).isEqualTo(660);
        assertThat(r.eta()).isEqualTo(Instant.parse("2026-11-03T08:47:00Z"));
    }

    @Test
    void aCrewOfTwoSwapsAndRestsNineHours() {
        HosPlanner.Result r = HosPlanner.plan(MONDAY_6, List.of(leg(1500, 1286, 0)),
                List.of(DriverState.fresh(MONDAY_6, UTC), DriverState.fresh(MONDAY_6, UTC)),
                new Options(true, true, false));
        assertThat(r.events().stream().filter(e -> e.kind() == Kind.BREAK)).isEmpty();
        Event rest = r.events().stream().filter(e -> e.kind() == Kind.DAILY_REST).findFirst().orElseThrow();
        assertThat(rest.minutes()).isEqualTo(540);
        assertThat(rest.start()).isEqualTo(Instant.parse("2026-11-03T02:00:00Z"));
        assertThat(r.eta()).isEqualTo(Instant.parse("2026-11-03T12:26:00Z"));
        assertThat(r.events().stream().filter(e -> e.kind() == Kind.DRIVE).map(Event::driver).distinct())
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void serviceThatWouldEndOutsideTheShiftComesAfterTheRest() {
        HosPlanner.Result r = HosPlanner.plan(MONDAY_6, List.of(leg(600, 520, 0), leg(0, 0, 0), leg(20, 20, 180)),
                List.of(DriverState.fresh(MONDAY_6, UTC)), new Options(false, false, false));
        // 06:00 + 520 min driving + 45 min break = 15:25, with the 20 min leg 15:45; 3 h unloading ends at 18:45,
        // inside the 13 h shift window (until 19:00). A 4 h unloading would not fit: the 11 h rest comes first.
        assertThat(kinds(r).getLast()).isEqualTo(Kind.SERVICE);
        HosPlanner.Result late = HosPlanner.plan(MONDAY_6, List.of(leg(600, 520, 0), leg(20, 20, 240)),
                List.of(DriverState.fresh(MONDAY_6, UTC)), new Options(false, false, false));
        assertThat(kinds(late).subList(kinds(late).size() - 2, kinds(late).size()))
                .containsExactly(Kind.DAILY_REST, Kind.SERVICE);
    }

    @Test
    void aLongTripNeedsAWeeklyRest() {
        HosPlanner.Result r = HosPlanner.plan(MONDAY_6, List.of(leg(4800, 4100, 0)),
                List.of(DriverState.fresh(MONDAY_6, UTC)), new Options(true, true, false));
        Event weekly = r.events().stream().filter(e -> e.kind() == Kind.WEEKLY_REST).findFirst().orElseThrow();
        assertThat(weekly.minutes()).isGreaterThanOrEqualTo(HosEngine.WEEKLY_REST);
        long dailyRests = r.events().stream().filter(e -> e.kind() == Kind.REDUCED_DAILY_REST).count();
        assertThat(dailyRests).as("at most three reduced daily rests per week").isLessThanOrEqualTo(6);
        int maxShiftDriving = 0;
        int shift = 0;
        for (Event e : r.events()) {
            if (e.kind() == Kind.DRIVE) {
                shift += e.minutes();
                maxShiftDriving = Math.max(maxShiftDriving, shift);
            } else if (e.kind() != Kind.BREAK) {
                shift = 0;
            }
        }
        assertThat(maxShiftDriving).isLessThanOrEqualTo(HosEngine.DAILY_DRIVING_EXTENDED);
    }

    @Test
    void theStateComesFromRecordedActivity() {
        List<HosEngine.Seg> recorded = List.of(
                new HosEngine.Seg(Instant.parse("2026-10-31T00:00:00Z"), Instant.parse("2026-11-02T06:00:00Z"),
                        HosEngine.Act.REST, 1, null),
                new HosEngine.Seg(Instant.parse("2026-11-02T06:00:00Z"), Instant.parse("2026-11-02T10:30:00Z"),
                        HosEngine.Act.DRIVING, 1, "WX1"),
                new HosEngine.Seg(Instant.parse("2026-11-02T10:30:00Z"), Instant.parse("2026-11-02T11:15:00Z"),
                        HosEngine.Act.REST, 1, "WX1"),
                new HosEngine.Seg(Instant.parse("2026-11-02T11:15:00Z"), Instant.parse("2026-11-02T14:00:00Z"),
                        HosEngine.Act.DRIVING, 1, "WX1"));
        Instant at = Instant.parse("2026-11-02T14:10:00Z");
        DriverState s = DriverState.fromRecorded(recorded, at, UTC);
        assertThat(s.source()).isEqualTo(DriverState.Source.TACHO);
        assertThat(s.drivingSinceBreakMin()).isEqualTo(165);
        assertThat(s.drivingInShiftMin()).isEqualTo(435);
        assertThat(s.shiftStart()).isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
        assertThat(s.weekDrivingMin()).isEqualTo(435);
        assertThat(s.weeklyRestDueAt()).isEqualTo(Instant.parse("2026-11-08T06:00:00Z"));
        assertThat(s.extensionsLeft()).isEqualTo(2);
        assertThat(s.dataUntil()).isEqualTo(Instant.parse("2026-11-02T14:00:00Z"));

        HosPlanner.Result r = HosPlanner.plan(at, List.of(leg(300, 260, 0)), List.of(s), new Options(false, false,
                false));
        // 105 min more reach both the 4.5 h break and the 9 h day: the daily rest covers the break
        assertThat(kinds(r)).containsExactly(Kind.DRIVE, Kind.DAILY_REST, Kind.DRIVE);
        assertThat(r.events().getFirst().minutes()).isEqualTo(105);
        assertThat(r.events().getLast().minutes()).isEqualTo(155);
    }
}
