package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;

import java.time.Instant;

/** VuOverSpeedingEventRecord; speeds in km/h. */
public record VuOverSpeedingEvent(int type, int purpose, Instant begin, Instant end, int maxSpeed,
                                  int averageSpeed, CardNumber driverCardBegin, int similarEvents) {
}
