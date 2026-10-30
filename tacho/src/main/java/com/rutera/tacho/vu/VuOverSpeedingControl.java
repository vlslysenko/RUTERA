package com.rutera.tacho.vu;

import java.time.Instant;

/** VuOverSpeedingControlData: the last over speeding control and the over speeding events since then. */
public record VuOverSpeedingControl(Instant lastControl, Instant firstOverSpeedSince, int overSpeedCountSince) {
}
