package com.rutera.tacho.card;

/**
 * EF Application_Identification (0501h), plus EF Application_Identification_V2 (0525h) for GEN2V2 cards. The
 * numbers are the capacities of the cyclic files; second generation fields are null on a GEN1 application.
 *
 * @param cardType TypeOfTachographCardId: 1 driver, 2 workshop, 3 control, 4 company card
 */
public record CardApplicationIdentification(int cardType, int structureVersion, int eventsPerType,
                                            int faultsPerType, int activityStructureLength, int vehicleRecords,
                                            int placeRecords, Integer gnssAdRecords,
                                            Integer specificConditionRecords, Integer vehicleUnitRecords,
                                            Integer borderCrossingRecords, Integer loadUnloadRecords,
                                            Integer loadTypeEntryRecords) {

    CardApplicationIdentification withV2(int borderCrossings, int loadUnloads, int loadTypeEntries) {
        return new CardApplicationIdentification(cardType, structureVersion, eventsPerType, faultsPerType,
                activityStructureLength, vehicleRecords, placeRecords, gnssAdRecords, specificConditionRecords,
                vehicleUnitRecords, borderCrossings, loadUnloads, loadTypeEntries);
    }
}
