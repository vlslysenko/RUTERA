package com.rutera.tacho.vu;

import java.time.Instant;

/** VuDetailedSpeedBlock: one minute of speeds, one value per second in km/h (60 values). */
public record VuSpeedBlock(Instant begin, int[] speeds) {
}
