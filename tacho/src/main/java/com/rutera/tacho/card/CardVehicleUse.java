package com.rutera.tacho.card;

import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;

/**
 * CardVehicleRecord of EF Vehicles_Used (0505h): a period of use of one vehicle.
 *
 * @param vin second generation only, otherwise null
 */
public record CardVehicleUse(Integer odometerBeginKm, Integer odometerEndKm, Instant firstUse, Instant lastUse,
                             VehicleRegistration registration, int vuDataBlockCounter, String vin) {
}
