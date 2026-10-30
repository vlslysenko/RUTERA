package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.GnssPlace;

import java.time.Instant;

/** VuGNSSADRecord: the vehicle position after every three hours of accumulated driving. */
public record VuGnssAccumulatedDriving(Instant timestamp, CardNumber driverCard, CardNumber coDriverCard,
                                       GnssPlace place, Integer odometerKm) {
}
