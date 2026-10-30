package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;

import java.time.Instant;

/** VuTimeAdjustmentRecord: a clock adjustment outside a full calibration. */
public record VuTimeAdjustment(Instant oldTime, Instant newTime, String workshopName, String workshopAddress,
                               CardNumber workshopCard) {
}
