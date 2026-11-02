package com.rutera.core.calc.plan;

import com.rutera.core.tacho.hos.HosEngine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plans a trip under Regulation (EC) 561/2006 from the drivers' states: drives as long as every limit allows, then
 * inserts the stop the binding limit needs (45 min break after 4.5 h, daily rest after 9 h or 10 h driving or at the
 * end of the 24 h shift window, weekly rest when due or when the weekly or fortnightly driving is used up), and the
 * service time (loading, unloading) at the end of each leg.
 * <ul>
 *   <li>One driver: daily rest 11 h, or 9 h while reductions are left and allowed; 10 h days while extensions are
 *       left and allowed.</li>
 *   <li>Two drivers (multi-manning): they swap at the wheel; co-driving of at least 45 min counts as the passive
 *       driver's break; the crew rests 9 h within 30 h of the end of its previous daily rest.</li>
 * </ul>
 * Not modelled: split daily rest (3 + 9 h), ferry or train interruptions, compensation of reduced weekly rests.
 */
public final class HosPlanner {

    public enum Kind {
        DRIVE, BREAK, DAILY_REST, REDUCED_DAILY_REST, WEEKLY_REST, REDUCED_WEEKLY_REST, SERVICE
    }

    /** A leg: driving time and distance, then {@code serviceMin} of work at its end (loading, unloading). */
    public record Leg(String name, double distanceKm, int driveMin, int serviceMin) {
    }

    public record Options(boolean extendedDriving, boolean reducedRests, boolean reducedWeeklyRest) {
    }

    /** {@code driver} is 1 or 2 for driving, null for stops of the whole crew. */
    public record Event(Kind kind, Instant start, Instant end, int minutes, double kmFrom, double kmTo,
                        int legIndex, Integer driver) {
    }

    public record Result(Instant eta, List<Event> events) {
    }

    private static final int MAX_STEPS = 20_000;
    private static final int CREW_PERIOD = 30 * 60;

    private final List<DriverState> crew;
    private final Options options;
    private final List<Event> events = new ArrayList<>();
    private Instant t;
    private double km;
    private int leg;
    private Instant crewShiftStart;
    private int active;

    private HosPlanner(List<DriverState> crew, Options options, Instant start) {
        this.crew = crew.stream().map(DriverState::copy).toList();
        this.options = options;
        this.t = start;
    }

    /** Plans the legs from {@code start} for one or two drivers (their states are not changed). */
    public static Result plan(Instant start, List<Leg> legs, List<DriverState> drivers, Options options) {
        if (drivers.isEmpty() || drivers.size() > 2) {
            throw new IllegalArgumentException("one or two drivers");
        }
        HosPlanner p = new HosPlanner(drivers, options, start);
        if (drivers.size() == 2) {
            Instant a = drivers.get(0).shiftStart;
            Instant b = drivers.get(1).shiftStart;
            p.crewShiftStart = a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
        }
        for (int i = 0; i < legs.size(); i++) {
            p.leg = i;
            Leg l = legs.get(i);
            double kmPerMin = l.driveMin() > 0 ? l.distanceKm() / l.driveMin() : 0;
            int remaining = l.driveMin();
            int steps = 0;
            while (remaining > 0) {
                if (++steps > MAX_STEPS) {
                    throw new IllegalStateException("no progress in the plan");
                }
                remaining -= drivers.size() == 1 ? p.stepSolo(remaining, kmPerMin) : p.stepCrew(remaining, kmPerMin);
            }
            if (l.driveMin() == 0) {
                p.km += l.distanceKm();
            }
            if (l.serviceMin() > 0) {
                p.service(l.serviceMin());
            }
        }
        return new Result(p.t, List.copyOf(p.events));
    }

    // --- one driver ----------------------------------------------------------------------------------------

    /** One move: drives as far as the limits allow, or takes the stop the binding limit needs. Returns minutes driven. */
    private int stepSolo(int remaining, double kmPerMin) {
        DriverState d = crew.getFirst();
        d.rollTo(t);
        if (weeklyBlocked(d)) {
            weeklyRest();
            return 0;
        }
        if (d.shiftStart == null) {
            d.shiftStart = t;
        }
        boolean reduce = reduceAllowed(d);
        long window = minutes(t, d.shiftStart.plus(Duration.ofMinutes(24 * 60 - restLength(reduce))));
        long toBreak = HosEngine.BREAK_AFTER - d.drivingSinceBreak;
        long toDaily = dailyLimit(d) - d.drivingInShift;
        long chunk = min(toBreak, toDaily, window, weekly(d), minutes(t, d.nextWeekStart()), remaining);
        if (chunk <= 0) {
            if (toDaily <= 0 || window <= 0) {
                dailyRest(reduce);
            } else {
                pause();
            }
            return 0;
        }
        driveSolo(d, (int) chunk, kmPerMin);
        return (int) chunk;
    }

    private void driveSolo(DriverState d, int minutes, double kmPerMin) {
        double from = km;
        km += minutes * kmPerMin;
        Instant end = t.plus(Duration.ofMinutes(minutes));
        d.drive(minutes);
        add(Kind.DRIVE, t, end, from, km, 1);
        t = end;
    }

    // --- crew of two ---------------------------------------------------------------------------------------

    private int stepCrew(int remaining, double kmPerMin) {
        for (DriverState d : crew) {
            d.rollTo(t);
        }
        if (weeklyBlocked(crew.get(0)) && weeklyBlocked(crew.get(1))
                || (crew.get(0).weeklyRestDueAt() != null && !t.isBefore(crew.get(0).weeklyRestDueAt()))
                || (crew.get(1).weeklyRestDueAt() != null && !t.isBefore(crew.get(1).weeklyRestDueAt()))) {
            weeklyRest();
            crewShiftStart = null;
            return 0;
        }
        if (crewShiftStart == null) {
            crewShiftStart = t;
        }
        for (DriverState d : crew) {
            if (d.shiftStart == null) {
                d.shiftStart = t;
            }
        }
        long window = minutes(t, crewShiftStart.plus(Duration.ofMinutes(CREW_PERIOD - HosEngine.DAILY_REST_REDUCED)));
        if (window <= 0) {
            crewRest();
            return 0;
        }
        long chunk = canDrive(crew.get(active), window, remaining);
        if (chunk <= 0) {
            int other = 1 - active;
            if (canDrive(crew.get(other), window, remaining) > 0) {
                active = other;
                return 0;
            }
            boolean dailyDone = crew.stream().allMatch(d -> dailyLimit(d) - d.drivingInShift <= 0 || weekly(d) <= 0);
            if (dailyDone) {
                crewRest();
            } else {
                pause();
            }
            return 0;
        }
        DriverState driver = crew.get(active);
        DriverState passive = crew.get(1 - active);
        double from = km;
        km += chunk * kmPerMin;
        Instant end = t.plus(Duration.ofMinutes(chunk));
        driver.drive((int) chunk);
        passive.breakTaken(chunk);
        add(Kind.DRIVE, t, end, from, km, active + 1);
        t = end;
        if (driver.drivingSinceBreak >= HosEngine.BREAK_AFTER || dailyLimit(driver) - driver.drivingInShift <= 0) {
            active = 1 - active;
        }
        return (int) chunk;
    }

    private long canDrive(DriverState d, long window, int remaining) {
        return min(HosEngine.BREAK_AFTER - d.drivingSinceBreak, dailyLimit(d) - d.drivingInShift, weekly(d),
                window, minutes(t, d.nextWeekStart()), remaining);
    }

    private void crewRest() {
        Instant end = t.plus(Duration.ofMinutes(HosEngine.DAILY_REST_REDUCED));
        for (DriverState d : crew) {
            d.closeShift();
            d.breakTaken(HosEngine.DAILY_REST_REDUCED);
        }
        add(Kind.DAILY_REST, t, end, km, km, null);
        t = end;
        crewShiftStart = null;
    }

    // --- stops ---------------------------------------------------------------------------------------------

    private void pause() {
        Instant end = t.plus(Duration.ofMinutes(HosEngine.BREAK_FULL));
        for (DriverState d : crew) {
            d.breakTaken(HosEngine.BREAK_FULL);
        }
        add(Kind.BREAK, t, end, km, km, null);
        t = end;
    }

    private void dailyRest(boolean reduce) {
        int len = restLength(reduce);
        Instant end = t.plus(Duration.ofMinutes(len));
        for (DriverState d : crew) {
            d.rest(t, end);
        }
        add(reduce ? Kind.REDUCED_DAILY_REST : Kind.DAILY_REST, t, end, km, km, null);
        t = end;
    }

    /** 45 h (24 h reduced when allowed), longer when the weekly or fortnightly driving only frees up next week. */
    private void weeklyRest() {
        int len = options.reducedWeeklyRest() ? HosEngine.WEEKLY_REST_REDUCED : HosEngine.WEEKLY_REST;
        Instant end = t.plus(Duration.ofMinutes(len));
        for (DriverState d : crew) {
            if (d.weekDriving >= HosEngine.WEEKLY_DRIVING
                    || d.weekDriving + d.prevWeekDriving >= HosEngine.TWO_WEEK_DRIVING) {
                Instant next = d.nextWeekStart();
                end = next.isAfter(end) ? next : end;
            }
        }
        for (DriverState d : crew) {
            d.rest(t, end);
        }
        add(options.reducedWeeklyRest() && minutes(t, end) < HosEngine.WEEKLY_REST ? Kind.REDUCED_WEEKLY_REST
                : Kind.WEEKLY_REST, t, end, km, km, null);
        t = end;
    }

    /** Loading or unloading: work inside the shift; when it would not end inside the shift window, rest first. */
    private void service(int minutes) {
        if (crew.size() == 1) {
            DriverState d = crew.getFirst();
            d.rollTo(t);
            boolean reduce = reduceAllowed(d);
            if (d.shiftStart != null && t.plus(Duration.ofMinutes(minutes))
                    .isAfter(d.shiftStart.plus(Duration.ofMinutes(24 * 60 - restLength(reduce))))) {
                dailyRest(reduce);
            }
            if (d.shiftStart == null) {
                d.shiftStart = t;
            }
        } else {
            if (crewShiftStart != null && t.plus(Duration.ofMinutes(minutes))
                    .isAfter(crewShiftStart.plus(Duration.ofMinutes(CREW_PERIOD - HosEngine.DAILY_REST_REDUCED)))) {
                crewRest();
            }
            if (crewShiftStart == null) {
                crewShiftStart = t;
            }
            for (DriverState d : crew) {
                if (d.shiftStart == null) {
                    d.shiftStart = t;
                }
            }
        }
        Instant end = t.plus(Duration.ofMinutes(minutes));
        add(Kind.SERVICE, t, end, km, km, null);
        t = end;
    }

    // --- limits --------------------------------------------------------------------------------------------

    /** 10 h when the shift already uses an extension, or one is left and allowed; else 9 h. */
    private int dailyLimit(DriverState d) {
        boolean extend = d.drivingInShift > HosEngine.DAILY_DRIVING
                || (options.extendedDriving() && d.extensionsUsed < HosEngine.EXTENSIONS_PER_WEEK);
        return extend ? HosEngine.DAILY_DRIVING_EXTENDED : HosEngine.DAILY_DRIVING;
    }

    private boolean reduceAllowed(DriverState d) {
        return options.reducedRests() && d.reducedRests < HosEngine.REDUCED_RESTS_BETWEEN_WEEKLY;
    }

    private static int restLength(boolean reduce) {
        return reduce ? HosEngine.DAILY_REST_REDUCED : HosEngine.DAILY_REST;
    }

    /** Driving left this week and fortnight, and until the weekly rest is due. */
    private long weekly(DriverState d) {
        long due = d.weeklyRestDueAt() == null ? Long.MAX_VALUE : minutes(t, d.weeklyRestDueAt());
        return Math.min(Math.min(HosEngine.WEEKLY_DRIVING - d.weekDriving,
                HosEngine.TWO_WEEK_DRIVING - d.weekDriving - d.prevWeekDriving), due);
    }

    private boolean weeklyBlocked(DriverState d) {
        return weekly(d) <= 0;
    }

    private void add(Kind kind, Instant start, Instant end, double kmFrom, double kmTo, Integer driver) {
        int minutes = (int) Duration.between(start, end).toMinutes();
        Event last = events.isEmpty() ? null : events.getLast();
        if (last != null && last.kind() == kind && last.end().equals(start) && last.legIndex() == leg
                && Objects.equals(last.driver(), driver) && kind == Kind.DRIVE) {
            events.set(events.size() - 1, new Event(kind, last.start(), end, last.minutes() + minutes,
                    last.kmFrom(), kmTo, leg, driver));
            return;
        }
        events.add(new Event(kind, start, end, minutes, kmFrom, kmTo, leg, driver));
    }

    private static long minutes(Instant from, Instant to) {
        return Duration.between(from, to).toMinutes();
    }

    private static long min(long a, long b, long c, long d, long e, long f) {
        return Math.min(Math.min(Math.min(a, b), Math.min(c, d)), Math.min(e, f));
    }
}
