package com.rutera.tacho.card;

import com.rutera.tacho.Generation;
import com.rutera.tacho.model.PlaceRecord;
import com.rutera.tacho.model.SpecificCondition;

import java.time.Instant;
import java.util.List;

/**
 * The data of one card application: DF Tachograph (GEN1, present on every card) or DF Tachograph_G2 (second
 * generation cards only). Lists are sorted by time; empty records of the cyclic files are left out. Second
 * generation lists are empty for a GEN1 application.
 */
public record CardApplication(
        Generation generation,
        CardApplicationIdentification applicationIdentification,
        CardIdentification identification,
        DriverCardHolder holder,
        DrivingLicence drivingLicence,
        Instant lastCardDownload,
        List<CardEventFault> events,
        List<CardEventFault> faults,
        List<CardActivityDay> activityDays,
        List<CardVehicleUse> vehiclesUsed,
        List<PlaceRecord> places,
        CardCurrentUse currentUse,
        CardControlActivity lastControl,
        List<SpecificCondition> specificConditions,
        List<CardVehicleUnitUse> vehicleUnitsUsed,
        List<CardGnssAccumulatedDriving> gnssAccumulatedDriving,
        List<CardBorderCrossing> borderCrossings,
        List<CardLoadUnload> loadUnloadOperations,
        List<CardLoadTypeEntry> loadTypeEntries) {
}
