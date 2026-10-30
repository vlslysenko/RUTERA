package com.rutera.tacho.card;

import com.rutera.tacho.model.GnssPlace;

import java.time.Instant;

/**
 * CardLoadUnloadRecord of EF Load_Unload_Operations (0529h, GEN2V2).
 *
 * @param operationType 1 load, 2 unload, 3 simultaneous load and unload
 */
public record CardLoadUnload(Instant timestamp, int operationType, GnssPlace place, Integer odometerKm) {
}
