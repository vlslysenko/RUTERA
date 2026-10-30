package com.rutera.tacho.vu;

import com.rutera.tacho.Generation;
import com.rutera.tacho.model.ActivityChange;
import com.rutera.tacho.model.ActivityPeriod;
import com.rutera.tacho.model.SpecificCondition;

import java.time.LocalDate;
import java.util.List;

/**
 * Transfer "activities of a specified date" (TREP 02h, 22h, 32h): one calendar day (UTC). The activity changes
 * cover both slots and start with the slot status at 00:00.
 */
public record VuActivityDay(Generation generation, LocalDate date, Integer odometerMidnightKm,
                            List<VuCardInsertion> cardInsertions, List<ActivityChange> changes,
                            List<VuPlace> places, List<VuGnssAccumulatedDriving> gnssAccumulatedDriving,
                            List<SpecificCondition> specificConditions, List<VuBorderCrossing> borderCrossings,
                            List<VuLoadUnload> loadUnloadOperations) {

    public List<ActivityPeriod> periods() {
        return ActivityPeriod.of(date, changes);
    }
}
