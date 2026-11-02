package com.rutera.core.calc.plan;

import com.rutera.core.tacho.hos.HosEngine;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The 561/2006 counters of one driver at a moment, in minutes: driving since the last break, the open shift
 * (started by the first activity after a daily rest), the current and the previous week (Monday 00:00 in the
 * firma's zone), daily driving extensions used this week, reduced daily rests since the last weekly rest and the end
 * of that weekly rest. Built from recorded tachograph activity ({@link #fromRecorded}) or fresh (just after a regular
 * weekly rest), and advanced by the {@link HosPlanner}.
 */
public final class DriverState {

    public enum Source {
        TACHO, FRESH, MANUAL
    }

    private final ZoneId zone;
    private final Source source;
    int drivingSinceBreak;
    boolean breakFirstPart;
    Instant shiftStart;
    int drivingInShift;
    LocalDate weekMonday;
    int weekDriving;
    int prevWeekDriving;
    int extensionsUsed;
    int reducedRests;
    Instant lastWeeklyRestEnd;
    Instant dataUntil;

    private DriverState(ZoneId zone, Source source, Instant at) {
        this.zone = zone;
        this.source = source;
        this.weekMonday = monday(at);
    }

    /** Just after a regular weekly rest: all counters zero, the next weekly rest due 144 h after {@code at}. */
    public static DriverState fresh(Instant at, ZoneId zone) {
        DriverState s = new DriverState(zone, Source.FRESH, at);
        s.lastWeeklyRestEnd = at;
        return s;
    }

    /** Counters given by hand; null values count as zero (or unknown for the dates). */
    public static DriverState manual(Instant at, ZoneId zone, int drivingSinceBreak, int drivingInShift,
                                     Instant shiftStart, int weekDriving, int fortnightDriving, int extensionsLeft,
                                     int reducedRestsLeft, Instant lastWeeklyRestEnd) {
        DriverState s = new DriverState(zone, Source.MANUAL, at);
        s.drivingSinceBreak = drivingSinceBreak;
        s.drivingInShift = drivingInShift;
        s.shiftStart = shiftStart != null ? shiftStart : drivingInShift > 0 ? at : null;
        s.weekDriving = weekDriving;
        s.prevWeekDriving = Math.max(0, fortnightDriving - weekDriving);
        s.extensionsUsed = Math.max(0, HosEngine.EXTENSIONS_PER_WEEK - extensionsLeft);
        s.reducedRests = Math.max(0, HosEngine.REDUCED_RESTS_BETWEEN_WEEKLY - reducedRestsLeft);
        s.lastWeeklyRestEnd = lastWeeklyRestEnd;
        return s;
    }

    /**
     * The state at {@code at} after the recorded segments (unknown time and gaps count as rest, the time between the
     * last record and {@code at} too). Segments after {@code at} are ignored.
     */
    public static DriverState fromRecorded(List<HosEngine.Seg> recorded, Instant at, ZoneId zone) {
        List<HosEngine.Seg> segs = new ArrayList<>(recorded.stream()
                .filter(s -> s.start().isBefore(at) && s.end().isAfter(s.start())).toList());
        segs.sort(Comparator.comparing(HosEngine.Seg::start));
        Instant first = segs.isEmpty() ? at : segs.getFirst().start();
        DriverState s = new DriverState(zone, Source.TACHO, first);
        Instant cursor = null;
        Instant restStart = null;
        for (HosEngine.Seg seg : segs) {
            Instant start = cursor != null && seg.start().isBefore(cursor) ? cursor : seg.start();
            Instant end = seg.end().isAfter(at) ? at : seg.end();
            if (!end.isAfter(start)) {
                continue;
            }
            boolean rest = seg.act() == HosEngine.Act.REST || seg.act() == HosEngine.Act.UNKNOWN;
            if (rest) {
                if (restStart == null) {
                    restStart = cursor != null && cursor.isBefore(start) ? cursor : start;
                }
            } else {
                Instant restFrom = restStart != null ? restStart : cursor != null && cursor.isBefore(start) ? cursor
                        : null;
                if (restFrom != null) {
                    s.rest(restFrom, start);
                }
                restStart = null;
                s.work(start, end, seg.act() == HosEngine.Act.DRIVING);
            }
            cursor = end;
        }
        if (cursor != null) {
            Instant restFrom = restStart != null ? restStart : cursor;
            if (at.isAfter(restFrom)) {
                s.rest(restFrom, at);
            }
        }
        s.dataUntil = cursor;
        s.rollTo(at);
        return s;
    }

    // --- transitions (used by the reducer and the planner) -------------------------------------------------

    /** Activity other than rest; driving minutes count per week (split at Monday 00:00). */
    void work(Instant start, Instant end, boolean driving) {
        rollTo(start);
        if (shiftStart == null) {
            shiftStart = start;
        }
        if (!driving) {
            return;
        }
        Instant t = start;
        while (t.isBefore(end)) {
            rollTo(t);
            Instant boundary = nextWeekStart();
            Instant pieceEnd = boundary.isBefore(end) ? boundary : end;
            int m = (int) Duration.between(t, pieceEnd).toMinutes();
            drive(m);
            t = pieceEnd;
        }
    }

    void drive(int minutes) {
        drivingSinceBreak += minutes;
        drivingInShift += minutes;
        weekDriving += minutes;
    }

    /** A rest block from {@code start} to {@code end}: break, daily or weekly rest by its length. */
    void rest(Instant start, Instant end) {
        long len = Duration.between(start, end).toMinutes();
        if (len >= HosEngine.WEEKLY_REST_REDUCED) {
            closeShift();
            lastWeeklyRestEnd = end;
            reducedRests = 0;
        } else if (len >= HosEngine.DAILY_REST_REDUCED) {
            closeShift();
            if (len < HosEngine.DAILY_REST) {
                reducedRests++;
            }
        }
        breakTaken(len);
        rollTo(end);
    }

    void breakTaken(long len) {
        if (len >= HosEngine.BREAK_FULL || (len >= HosEngine.BREAK_SECOND_PART && breakFirstPart)) {
            drivingSinceBreak = 0;
            breakFirstPart = false;
        } else if (len >= HosEngine.BREAK_FIRST_PART) {
            breakFirstPart = true;
        }
    }

    /** Ends the open shift; a shift with more than 9 h driving used an extension of the week it started in. */
    void closeShift() {
        if (drivingInShift > HosEngine.DAILY_DRIVING) {
            extensionsUsed++;
        }
        shiftStart = null;
        drivingInShift = 0;
        drivingSinceBreak = 0;
        breakFirstPart = false;
    }

    /** Moves the week counters forward to the week of {@code t}. */
    void rollTo(Instant t) {
        LocalDate target = monday(t);
        while (weekMonday.isBefore(target)) {
            weekMonday = weekMonday.plusWeeks(1);
            prevWeekDriving = weekDriving;
            weekDriving = 0;
            extensionsUsed = 0;
        }
    }

    Instant nextWeekStart() {
        return weekMonday.plusWeeks(1).atStartOfDay(zone).toInstant();
    }

    private LocalDate monday(Instant t) {
        return LocalDate.ofInstant(t, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    DriverState copy() {
        DriverState c = new DriverState(zone, source, Instant.EPOCH);
        c.drivingSinceBreak = drivingSinceBreak;
        c.breakFirstPart = breakFirstPart;
        c.shiftStart = shiftStart;
        c.drivingInShift = drivingInShift;
        c.weekMonday = weekMonday;
        c.weekDriving = weekDriving;
        c.prevWeekDriving = prevWeekDriving;
        c.extensionsUsed = extensionsUsed;
        c.reducedRests = reducedRests;
        c.lastWeeklyRestEnd = lastWeeklyRestEnd;
        c.dataUntil = dataUntil;
        return c;
    }

    // --- read access ---------------------------------------------------------------------------------------

    public Source source() {
        return source;
    }

    public ZoneId zone() {
        return zone;
    }

    public int drivingSinceBreakMin() {
        return drivingSinceBreak;
    }

    public int drivingInShiftMin() {
        return drivingInShift;
    }

    public Instant shiftStart() {
        return shiftStart;
    }

    public int weekDrivingMin() {
        return weekDriving;
    }

    public int fortnightDrivingMin() {
        return weekDriving + prevWeekDriving;
    }

    public int extensionsLeft() {
        return Math.max(0, HosEngine.EXTENSIONS_PER_WEEK - extensionsUsed);
    }

    public int reducedRestsLeft() {
        return Math.max(0, HosEngine.REDUCED_RESTS_BETWEEN_WEEKLY - reducedRests);
    }

    /** 144 h after the end of the last weekly rest; null when no weekly rest is known. */
    public Instant weeklyRestDueAt() {
        return lastWeeklyRestEnd == null ? null : lastWeeklyRestEnd.plus(Duration.ofMinutes(HosEngine.WEEKLY_REST_DUE_AFTER));
    }

    public Instant dataUntil() {
        return dataUntil;
    }
}
