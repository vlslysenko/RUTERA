package com.rutera.tacho.card;

import java.time.Instant;

/** CardVehicleUnitRecord of EF VehicleUnits_Used (0523h, second generation): which VU the card was used in. */
public record CardVehicleUnitUse(Instant timestamp, int manufacturerCode, int deviceId, String softwareVersion) {
}
