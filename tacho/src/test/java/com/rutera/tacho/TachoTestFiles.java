package com.rutera.tacho;

/**
 * Synthetic DDD files for the tests of other modules (shipped in the test-jar of rutera-tacho). They carry no
 * personal data: card number {@link #CARD_NUMBER}, holder "TESTOWY JAN", VIN {@link #VIN}, plate {@link #PLATE}.
 */
public final class TachoTestFiles {

    /** Card number of every driver card fixture (driver identification {@code TST00000000001}). */
    public static final String CARD_NUMBER = CardFixtures.CARD_NUMBER;
    public static final String VIN = VuFixtures.VIN;
    public static final String PLATE = "TEST123";

    private TachoTestFiles() {
    }

    public static byte[] gen1DriverCard() {
        return CardFixtures.gen1Card();
    }

    public static byte[] gen2DriverCard() {
        return CardFixtures.gen2Card(false);
    }

    public static byte[] companyCard() {
        return CardFixtures.companyCard();
    }

    /** A GEN1 card whose activity file is damaged: parsed with warnings. */
    public static byte[] cardWithWarnings() {
        return CardFixtures.cardWithBrokenActivity();
    }

    public static byte[] gen1VehicleUnit() {
        return VuFixtures.gen1Vu();
    }

    public static byte[] gen1VehicleUnitOverviewOnly() {
        return VuFixtures.gen1OverviewOnly();
    }
}
