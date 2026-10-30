package com.rutera.tacho.model;

import com.rutera.tacho.io.ByteReader;

/**
 * FullCardNumber (18 bytes) or FullCardNumberAndGeneration (19 bytes): card type, issuing member state and the 16
 * character card number. For a driver card the number is the 14 character driver identification, the replacement
 * index and the renewal index.
 *
 * @param equipmentType 1 driver, 2 workshop, 3 control, 4 company card (EquipmentType)
 * @param generation 1 or 2 for FullCardNumberAndGeneration, otherwise null
 */
public record CardNumber(int equipmentType, Nation issuingNation, String number, Integer generation) {

    public static final int DRIVER_CARD = 1;
    public static final int WORKSHOP_CARD = 2;
    public static final int CONTROL_CARD = 3;
    public static final int COMPANY_CARD = 4;

    /** FullCardNumber; null when the slot was empty (no number). */
    public static CardNumber read(ByteReader r) {
        int type = r.u8();
        int nation = r.u8();
        String number = r.ia5(16);
        if (number == null || type == 0 || type == 0xFF) {
            return null;
        }
        return new CardNumber(type, Nation.of(nation), number, null);
    }

    /** FullCardNumberAndGeneration (second generation). */
    public static CardNumber readWithGeneration(ByteReader r) {
        CardNumber n = read(r);
        int gen = r.u8();
        return n == null ? null : new CardNumber(n.equipmentType, n.issuingNation, n.number, gen);
    }

    public boolean isDriverCard() {
        return equipmentType == DRIVER_CARD;
    }

    /** Driver identification: the first 14 characters of a driver card number. */
    public String driverIdentification() {
        return number.length() >= 14 ? number.substring(0, 14) : number;
    }
}
