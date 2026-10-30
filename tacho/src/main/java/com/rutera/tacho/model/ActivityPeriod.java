package com.rutera.tacho.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * An activity interval [start, end) in UTC, built from the activity changes of one day. This is the shape stored in
 * {@code tacho_activity} and used by the 561/2006 rules.
 */
public record ActivityPeriod(Instant start, Instant end, Slot slot, Activity activity, boolean crew,
                             boolean cardPresent) {

    public long minutes() {
        return (end.getEpochSecond() - start.getEpochSecond()) / 60;
    }

    /**
     * Intervals of one day per slot: each change lasts until the next change of the same slot or 24:00. Changes
     * that go back in time or lie outside the day are ignored; zero-length intervals are dropped.
     */
    public static List<ActivityPeriod> of(LocalDate day, List<ActivityChange> changes) {
        List<ActivityPeriod> result = new ArrayList<>();
        if (day == null) {
            return result;
        }
        Instant midnight = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        for (Slot slot : Slot.values()) {
            ActivityChange current = null;
            for (ActivityChange c : changes) {
                if (c.slot() != slot || c.minuteOfDay() >= 1440) {
                    continue;
                }
                if (current != null && c.minuteOfDay() < current.minuteOfDay()) {
                    continue;
                }
                if (current != null) {
                    add(result, midnight, current, c.minuteOfDay());
                }
                current = c;
            }
            if (current != null) {
                add(result, midnight, current, 1440);
            }
        }
        result.sort((a, b) -> a.start.compareTo(b.start));
        return result;
    }

    private static void add(List<ActivityPeriod> out, Instant midnight, ActivityChange c, int endMinute) {
        if (endMinute <= c.minuteOfDay()) {
            return;
        }
        out.add(new ActivityPeriod(midnight.plusSeconds(c.minuteOfDay() * 60L), midnight.plusSeconds(endMinute * 60L),
                c.slot(), c.activity(), c.crew(), c.cardPresent()));
    }
}
