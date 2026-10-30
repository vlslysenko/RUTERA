package com.rutera.tacho.card;

import com.rutera.tacho.model.GnssPlace;

import java.time.Instant;

/**
 * GNSSAccumulatedDrivingRecord of EF GNSS_Places (0524h, second generation): the vehicle position each time the
 * accumulated driving time reaches a multiple of three hours.
 */
public record CardGnssAccumulatedDriving(Instant timestamp, GnssPlace place, Integer odometerKm) {
}
