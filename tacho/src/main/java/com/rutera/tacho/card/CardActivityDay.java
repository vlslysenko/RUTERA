package com.rutera.tacho.card;

import com.rutera.tacho.model.ActivityChange;
import com.rutera.tacho.model.ActivityPeriod;

import java.time.LocalDate;
import java.util.List;

/**
 * CardActivityDailyRecord of EF Driver_Activity_Data (0504h): one calendar day (UTC).
 *
 * @param presenceCounter activityDailyPresenceCounter, -1 if not BCD
 * @param distanceKm activityDayDistance
 */
public record CardActivityDay(LocalDate date, int presenceCounter, int distanceKm, List<ActivityChange> changes) {

    public List<ActivityPeriod> periods() {
        return ActivityPeriod.of(date, changes);
    }
}
