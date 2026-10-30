package com.rutera.tacho.card;

import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;

/** EF Current_Usage (0507h): the session open at the moment of the download (or the last one). */
public record CardCurrentUse(Instant sessionOpenTime, VehicleRegistration vehicle) {
}
