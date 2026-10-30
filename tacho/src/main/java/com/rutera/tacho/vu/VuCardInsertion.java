package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.Slot;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;

/**
 * VuCardIWRecord: one card insertion and withdrawal cycle; {@code withdrawalTime} is null while the card is still
 * inserted.
 *
 * @param previousVehicle the vehicle the card was used in before, with the time it was withdrawn there
 */
public record VuCardInsertion(String holderSurname, String holderFirstNames, CardNumber card, Instant cardExpiry,
                              Instant insertionTime, Integer odometerAtInsertionKm, Slot slot,
                              Instant withdrawalTime, Integer odometerAtWithdrawalKm,
                              VehicleRegistration previousVehicle, Instant previousVehicleWithdrawalTime,
                              boolean manualInput) {
}
