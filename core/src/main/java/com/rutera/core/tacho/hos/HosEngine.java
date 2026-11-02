package com.rutera.core.tacho.hos;

import com.rutera.common.domain.DriverViolation.Rule;
import com.rutera.common.domain.DriverViolation.Severity;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Driving and rest rules of Regulation (EC) 561/2006 on one driver's merged timeline, without Spring and without a
 * database. Severity follows the thresholds of Regulation (EU) 2016/403, Annex I (simplified, see {@link #severity}).
 * <p>
 * Model: unrecorded time and UNKNOWN (card withdrawn without a manual entry) count as rest. A rest block of at least
 * 9 h ends a shift (daily working period); a block of at least 24 h is a weekly rest. Per shift: daily driving (9 h,
 * 10 h twice per calendar week), the 45-minute break after 4 h 30 of driving (15 + 30 split allowed), the daily rest
 * within 24 h of the shift start (11 h, 3 + 9 h split, 9 h reduced at most three times between weekly rests). Per
 * calendar week (Monday 00:00 to Sunday 24:00 in the firma's time zone): 56 h driving, 90 h in two consecutive weeks.
 * Weekly rest: at least 24 h within six 24-hour periods after the previous weekly rest, never two reduced (< 45 h)
 * weekly rests in a row. Not modelled yet: compensation of reduced weekly rests, the 2020 exceptions for
 * international transport, ferry/train interruptions, multi-manning specifics.
 */
public final class HosEngine {

    public static final int DAILY_DRIVING = 540;
    public static final int DAILY_DRIVING_EXTENDED = 600;
    public static final int EXTENSIONS_PER_WEEK = 2;
    public static final int BREAK_AFTER = 270;
    public static final int BREAK_FULL = 45;
    public static final int BREAK_FIRST_PART = 15;
    public static final int BREAK_SECOND_PART = 30;
    public static final int DAILY_REST = 660;
    public static final int DAILY_REST_REDUCED = 540;
    public static final int SPLIT_REST_FIRST_PART = 180;
    public static final int REDUCED_RESTS_BETWEEN_WEEKLY = 3;
    public static final int WEEKLY_REST = 2700;
    public static final int WEEKLY_REST_REDUCED = 1440;
    public static final int WEEKLY_REST_DUE_AFTER = 144 * 60;
    public static final int WEEKLY_DRIVING = 56 * 60;
    public static final int TWO_WEEK_DRIVING = 90 * 60;

    public enum Act {
        DRIVING, WORK, AVAILABILITY, REST, UNKNOWN
    }

    /** A recorded interval of the driver: [start, end), slot 1 or 2, plate of the vehicle when known. */
    public record Seg(Instant start, Instant end, Act act, int slot, String plate) {

        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }

        boolean isRest() {
            return act == Act.REST || act == Act.UNKNOWN;
        }
    }

    /** A violation found by the rules. */
    public record Found(Rule rule, Severity severity, Instant start, Instant end, int actual, int limit, String plate,
                        String details) {
    }

    /** Figures of one calendar day (violations are counted by the caller). */
    public record Day(LocalDate day, int drivingMin, int workMin, int availabilityMin, int restMin,
                      Integer dailyRestMin, int extDrivingInWeek, int reducedRestInWeek, int weeklyDrivingMin,
                      int biweeklyDrivingMin, int remainingDailyMin, int remainingWeeklyMin,
                      Instant nextWeeklyRestBy) {
    }

    private record Block(Instant start, Instant end, boolean rest) {

        long minutes() {
            return Duration.between(start, end).toMinutes();
        }
    }

    private static final class Shift {
        Instant start;
        Instant end;
        Block closing;
        int driving;
        boolean extended;
        int reducedAfter;
        /** False for a shift that starts with the data: its real start (and its daily rest window) is unknown. */
        boolean knownStart;
    }

    private final ZoneId zone;
    private final List<Seg> recorded;
    private final List<Block> blocks = new ArrayList<>();
    private final List<Block> weeklyRests = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();
    private final List<Found> found = new ArrayList<>();
    private final Map<Instant, Integer> extensionsOfWeek = new HashMap<>();
    private final TreeMap<Instant, Integer> drivingOfWeek = new TreeMap<>();

    private HosEngine(List<Seg> recorded, ZoneId zone) {
        this.zone = zone;
        this.recorded = clean(recorded);
    }

    /** Runs every rule on the timeline; the result answers {@link #violations()} and {@link #days}. */
    public static HosEngine run(List<Seg> recorded, ZoneId zone) {
        HosEngine engine = new HosEngine(recorded, zone);
        engine.analyse();
        return engine;
    }

    public List<Found> violations() {
        return found;
    }

    // --- timeline ------------------------------------------------------------------------------------------

    /** Sorted, without zero-length intervals and without overlaps (a later interval cuts the earlier one). */
    private static List<Seg> clean(List<Seg> input) {
        List<Seg> sorted = new ArrayList<>(input.stream().filter(s -> s.end().isAfter(s.start())).toList());
        sorted.sort(Comparator.comparing(Seg::start).thenComparing(Seg::end));
        List<Seg> out = new ArrayList<>();
        for (Seg s : sorted) {
            if (!out.isEmpty()) {
                Seg last = out.getLast();
                if (!s.start().isBefore(last.end())) {
                    out.add(s);
                    continue;
                }
                if (!s.end().isAfter(last.end())) {
                    continue;
                }
                s = new Seg(last.end(), s.end(), s.act(), s.slot(), s.plate());
            }
            out.add(s);
        }
        return out;
    }

    /** Rest and non-rest blocks from the first to the last recorded minute; gaps are rest. */
    private void buildBlocks() {
        Instant cursor = null;
        for (Seg s : recorded) {
            if (cursor != null && s.start().isAfter(cursor)) {
                add(new Block(cursor, s.start(), true));
            }
            add(new Block(s.start(), s.end(), s.isRest()));
            cursor = s.end();
        }
    }

    private void add(Block b) {
        if (!blocks.isEmpty()) {
            Block last = blocks.getLast();
            if (last.rest() == b.rest() && last.end().equals(b.start())) {
                blocks.set(blocks.size() - 1, new Block(last.start(), b.end(), b.rest()));
                return;
            }
        }
        blocks.add(b);
    }

    // --- analysis ------------------------------------------------------------------------------------------

    private void analyse() {
        if (recorded.isEmpty()) {
            return;
        }
        buildBlocks();
        for (Block b : blocks) {
            if (b.rest() && b.minutes() >= WEEKLY_REST_REDUCED) {
                weeklyRests.add(b);
            }
        }
        buildShifts();
        weeklyDriving();
        int reduced = 0;
        for (Shift shift : shifts) {
            shift.driving = driving(shift.start, shift.end);
            dailyDriving(shift);
            breaks(shift);
            if (shift.closing != null) {
                if (shift.knownStart) {
                    reduced = dailyRest(shift, reduced);
                }
                if (shift.closing.minutes() >= WEEKLY_REST_REDUCED) {
                    reduced = 0;
                }
            } else if (shift.knownStart && shift.end.isAfter(shift.start.plus(Duration.ofHours(24)))) {
                // The data goes on for more than 24 h without a rest of 9 h.
                reduced = missingDailyRest(shift, reduced);
            }
            shift.reducedAfter = reduced;
        }
        weeklyRest();
        found.sort(Comparator.comparing(Found::start).thenComparing(f -> f.rule().ordinal()));
    }

    /** Shifts lie between rest blocks of at least 9 h. */
    private void buildShifts() {
        Instant start = blocks.getFirst().start();
        boolean known = false;
        for (Block b : blocks) {
            if (!b.rest() || b.minutes() < DAILY_REST_REDUCED) {
                continue;
            }
            if (b.start().isAfter(start)) {
                Shift s = new Shift();
                s.start = start;
                s.end = b.start();
                s.closing = b;
                s.knownStart = known;
                shifts.add(s);
            }
            start = b.end();
            known = true;
        }
        Instant end = blocks.getLast().end();
        if (end.isAfter(start)) {
            Shift s = new Shift();
            s.start = start;
            s.end = end;
            s.knownStart = known;
            shifts.add(s);
        }
    }

    private void dailyDriving(Shift shift) {
        if (shift.driving <= DAILY_DRIVING) {
            return;
        }
        Instant week = weekStart(shift.start);
        int used = extensionsOfWeek.getOrDefault(week, 0);
        if (used < EXTENSIONS_PER_WEEK) {
            extensionsOfWeek.put(week, used + 1);
            shift.extended = true;
            if (shift.driving > DAILY_DRIVING_EXTENDED) {
                report(Rule.DAILY_DRIVING_10H, shift.start, shift.end, shift.driving, DAILY_DRIVING_EXTENDED,
                        "extension " + (used + 1) + " of " + EXTENSIONS_PER_WEEK + " in the week");
            }
        } else {
            report(Rule.DAILY_DRIVING_9H, shift.start, shift.end, shift.driving, DAILY_DRIVING,
                    "no extension left in the week (" + EXTENSIONS_PER_WEEK + " used)");
        }
    }

    /** At most 4 h 30 of driving before a break of 45 min (or 15 min followed later by 30 min). */
    private void breaks(Shift shift) {
        int sum = 0;
        boolean firstPart = false;
        Instant since = shift.start;
        for (Block b : blocks) {
            if (!b.end().isAfter(shift.start) || !b.start().isBefore(shift.end)) {
                continue;
            }
            if (b.rest()) {
                long length = b.minutes();
                if (length >= BREAK_FULL || (firstPart && length >= BREAK_SECOND_PART)) {
                    if (sum > BREAK_AFTER) {
                        report(Rule.BREAK_45_AFTER_4H30, since, b.start(), sum, BREAK_AFTER,
                                "driving before a qualifying break");
                    }
                    sum = 0;
                    firstPart = false;
                    since = b.end();
                } else if (length >= BREAK_FIRST_PART) {
                    firstPart = true;
                }
            } else {
                sum += driving(max(b.start(), shift.start), min(b.end(), shift.end));
            }
        }
        if (sum > BREAK_AFTER) {
            report(Rule.BREAK_45_AFTER_4H30, since, shift.end, sum, BREAK_AFTER, "driving before the end of the shift");
        }
    }

    /**
     * The rest within 24 h of the shift start: 11 h (or 3 h + 9 h split), 9 h at most three times between weekly
     * rests. Returns the reduced rests used since the last weekly rest.
     */
    private int dailyRest(Shift shift, int reduced) {
        Instant windowEnd = shift.start.plus(Duration.ofHours(24));
        int closing = (int) overlap(shift.closing.start(), shift.closing.end(), shift.start, windowEnd);
        boolean split = false;
        int longest = closing;
        for (Block b : blocks) {
            if (!b.rest() || !b.start().isBefore(windowEnd) || !b.end().isAfter(shift.start)) {
                continue;
            }
            longest = (int) Math.max(longest, overlap(b.start(), b.end(), shift.start, windowEnd));
            if (b.end().compareTo(shift.closing.start()) <= 0 && !b.start().isBefore(shift.start)
                    && b.minutes() >= SPLIT_REST_FIRST_PART) {
                split = true;
            }
        }
        if (closing >= DAILY_REST || (split && closing >= DAILY_REST_REDUCED)) {
            return reduced;
        }
        if (closing >= DAILY_REST_REDUCED) {
            reduced++;
            if (reduced > REDUCED_RESTS_BETWEEN_WEEKLY) {
                report(Rule.DAILY_REST_11H, shift.start, windowEnd, closing, DAILY_REST,
                        "reduced daily rest " + reduced + " since the last weekly rest");
            }
            return reduced;
        }
        if (reduced < REDUCED_RESTS_BETWEEN_WEEKLY) {
            report(Rule.DAILY_REST_9H, shift.start, windowEnd, longest, DAILY_REST_REDUCED,
                    "longest rest within 24 h of the shift start");
        } else {
            report(Rule.DAILY_REST_11H, shift.start, windowEnd, longest, DAILY_REST,
                    "no reduced daily rest left since the last weekly rest");
        }
        return reduced + 1;
    }

    private int missingDailyRest(Shift shift, int reduced) {
        Instant windowEnd = shift.start.plus(Duration.ofHours(24));
        int longest = longestRest(shift.start, windowEnd);
        if (reduced < REDUCED_RESTS_BETWEEN_WEEKLY) {
            report(Rule.DAILY_REST_9H, shift.start, windowEnd, longest, DAILY_REST_REDUCED,
                    "no rest of 9 h within 24 h of the shift start");
        } else {
            report(Rule.DAILY_REST_11H, shift.start, windowEnd, longest, DAILY_REST,
                    "no rest of 11 h within 24 h of the shift start");
        }
        return reduced + 1;
    }

    private void weeklyDriving() {
        Instant week = weekStart(recorded.getFirst().start());
        Instant last = recorded.getLast().end();
        while (week.isBefore(last)) {
            Instant next = nextWeek(week);
            drivingOfWeek.put(week, driving(week, next));
            week = next;
        }
        for (Map.Entry<Instant, Integer> e : drivingOfWeek.entrySet()) {
            Instant start = e.getKey();
            Instant end = nextWeek(start);
            if (e.getValue() > WEEKLY_DRIVING) {
                report(Rule.WEEKLY_DRIVING_56H, start, end, e.getValue(), WEEKLY_DRIVING, "calendar week");
            }
            Integer following = drivingOfWeek.get(end);
            if (following != null && e.getValue() + following > TWO_WEEK_DRIVING) {
                report(Rule.TWO_WEEK_DRIVING_90H, start, nextWeek(end), e.getValue() + following, TWO_WEEK_DRIVING,
                        "two consecutive calendar weeks");
            }
        }
    }

    private void weeklyRest() {
        Instant timelineEnd = blocks.getLast().end();
        for (int i = 0; i < weeklyRests.size(); i++) {
            Block previous = weeklyRests.get(i);
            Instant due = previous.end().plus(Duration.ofMinutes(WEEKLY_REST_DUE_AFTER));
            Block next = i + 1 < weeklyRests.size() ? weeklyRests.get(i + 1) : null;
            Instant until = next == null ? timelineEnd : next.start();
            if (until.isAfter(due)) {
                report(Rule.WEEKLY_REST_24H, previous.end(), until, longestRest(previous.end(), until),
                        WEEKLY_REST_REDUCED, "no weekly rest within six 24-hour periods");
            }
            if (next != null && previous.minutes() < WEEKLY_REST && next.minutes() < WEEKLY_REST) {
                report(Rule.WEEKLY_REST_45H, next.start(), next.end(), (int) next.minutes(), WEEKLY_REST,
                        "second reduced weekly rest in a row");
            }
        }
    }

    // --- days ----------------------------------------------------------------------------------------------

    /** Figures of every calendar day of [from, to] in the firma's time zone. */
    public List<Day> days(LocalDate from, LocalDate to) {
        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(day(d));
        }
        return days;
    }

    private Day day(LocalDate d) {
        Instant start = d.atStartOfDay(zone).toInstant();
        Instant end = d.plusDays(1).atStartOfDay(zone).toInstant();
        int driving = 0;
        int work = 0;
        int availability = 0;
        int rest = 0;
        for (Seg s : recorded) {
            int m = (int) overlap(s.start(), s.end(), start, end);
            if (m == 0) {
                continue;
            }
            switch (s.act()) {
                case DRIVING -> driving += m;
                case WORK -> work += m;
                case AVAILABILITY -> availability += m;
                case REST, UNKNOWN -> rest += m;
            }
        }
        Integer dailyRest = null;
        for (Block b : blocks) {
            if (b.rest() && b.minutes() >= DAILY_REST_REDUCED && !b.start().isBefore(start) && b.start().isBefore(end)) {
                dailyRest = (int) b.minutes();
            }
        }
        Instant week = weekStart(start);
        int ext = 0;
        Shift last = null;
        int reduced = 0;
        for (Shift s : shifts) {
            if (!s.start.isBefore(end)) {
                break;
            }
            last = s;
            if (s.extended && !s.start.isBefore(week)) {
                ext++;
            }
            if (s.closing != null && s.closing.start().isBefore(end)) {
                reduced = s.reducedAfter;
            }
        }
        int weekly = recorded.isEmpty() ? 0 : driving(week, min(end, nextWeek(week)));
        Instant previousWeek = week.atZone(zone).minusWeeks(1).toInstant();
        int biweekly = weekly + (recorded.isEmpty() ? 0 : driving(previousWeek, week));
        int remainingDaily = 0;
        if (last != null) {
            boolean extensionLeft = last.extended
                    || extensionsOfWeek.getOrDefault(weekStart(last.start), 0) < EXTENSIONS_PER_WEEK;
            int allowed = extensionLeft ? DAILY_DRIVING_EXTENDED : DAILY_DRIVING;
            remainingDaily = Math.max(0, allowed - driving(last.start, min(last.end, end)));
        } else {
            remainingDaily = DAILY_DRIVING_EXTENDED;
        }
        int remainingWeekly = Math.max(0, Math.min(WEEKLY_DRIVING - weekly, TWO_WEEK_DRIVING - biweekly));
        remainingDaily = Math.min(remainingDaily, remainingWeekly);
        Instant nextWeeklyRestBy = null;
        for (Block w : weeklyRests) {
            if (!w.end().isAfter(end)) {
                nextWeeklyRestBy = w.end().plus(Duration.ofMinutes(WEEKLY_REST_DUE_AFTER));
            }
        }
        return new Day(d, driving, work, availability, rest, dailyRest, ext, reduced, weekly, biweekly, remainingDaily,
                remainingWeekly, nextWeeklyRestBy);
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private void report(Rule rule, Instant start, Instant end, int actual, int limit, String details) {
        found.add(new Found(rule, severity(rule, actual), start, end, actual, limit, plate(start, end), details));
    }

    /**
     * Severity by Regulation (EU) 2016/403, Annex I (thresholds in minutes, simplified to one table per rule):
     * MINOR, SERIOUS, VERY_SERIOUS and MOST_SERIOUS from the measured value.
     */
    static Severity severity(Rule rule, int actual) {
        return switch (rule) {
            case DAILY_DRIVING_9H -> over(actual, 600, 660, 810);
            case DAILY_DRIVING_10H -> over(actual, 660, 720, 900);
            case WEEKLY_DRIVING_56H -> over(actual, 3600, 3900, 4200);
            case TWO_WEEK_DRIVING_90H -> over(actual, 6000, 6300, 6750);
            case BREAK_45_AFTER_4H30 -> over(actual, 300, 360, 405);
            case DAILY_REST_11H -> under(actual, 600, 510, 330);
            case DAILY_REST_9H -> under(actual, 480, 420, 270);
            case WEEKLY_REST_45H -> under(actual, 2520, 2160, 1350);
            case WEEKLY_REST_24H -> under(actual, 1320, 1200, 720);
        };
    }

    /** Above the limit: below a -> MINOR, below b -> SERIOUS, below c -> VERY_SERIOUS, else MOST_SERIOUS. */
    private static Severity over(int actual, int a, int b, int c) {
        return actual < a ? Severity.MINOR : actual < b ? Severity.SERIOUS : actual < c ? Severity.VERY_SERIOUS
                : Severity.MOST_SERIOUS;
    }

    /** Below the limit: at least a -> MINOR, at least b -> SERIOUS, at least c -> VERY_SERIOUS, else MOST_SERIOUS. */
    private static Severity under(int actual, int a, int b, int c) {
        return actual >= a ? Severity.MINOR : actual >= b ? Severity.SERIOUS : actual >= c ? Severity.VERY_SERIOUS
                : Severity.MOST_SERIOUS;
    }

    private int driving(Instant from, Instant to) {
        long sum = 0;
        for (Seg s : recorded) {
            if (s.act() == Act.DRIVING) {
                sum += overlap(s.start(), s.end(), from, to);
            }
        }
        return (int) sum;
    }

    private int longestRest(Instant from, Instant to) {
        long longest = 0;
        for (Block b : blocks) {
            if (b.rest()) {
                longest = Math.max(longest, overlap(b.start(), b.end(), from, to));
            }
        }
        return (int) longest;
    }

    /** The plate of the last driving (or any) interval within [start, end), for the violation list. */
    private String plate(Instant start, Instant end) {
        String any = null;
        String driving = null;
        for (Seg s : recorded) {
            if (s.plate() == null || overlap(s.start(), s.end(), start, end) == 0) {
                continue;
            }
            any = s.plate();
            if (s.act() == Act.DRIVING) {
                driving = s.plate();
            }
        }
        return driving != null ? driving : any;
    }

    private Instant weekStart(Instant t) {
        return t.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(zone).toInstant();
    }

    private Instant nextWeek(Instant weekStart) {
        return weekStart.atZone(zone).toLocalDate().plusWeeks(1).atStartOfDay(zone).toInstant();
    }

    private static long overlap(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        Instant s = max(aStart, bStart);
        Instant e = min(aEnd, bEnd);
        return e.isAfter(s) ? Duration.between(s, e).toMinutes() : 0;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
