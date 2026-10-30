package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.GnssPlace;

import java.time.Instant;

/**
 * VuLoadUnloadRecord (GEN2V2).
 *
 * @param operationType 1 load, 2 unload, 3 simultaneous load and unload
 */
public record VuLoadUnload(Instant timestamp, int operationType, CardNumber driverCard, CardNumber coDriverCard,
                           GnssPlace place, Integer odometerKm) {
}
