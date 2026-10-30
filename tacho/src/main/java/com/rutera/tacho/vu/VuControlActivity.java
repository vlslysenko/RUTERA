package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;

import java.time.Instant;

/**
 * VuControlActivityRecord: a control made with this VU.
 *
 * @param controlType bits 'cvdt0000': card downloaded, VU downloaded, printing, display
 */
public record VuControlActivity(int controlType, Instant controlTime, CardNumber controlCard,
                                Instant downloadPeriodBegin, Instant downloadPeriodEnd) {
}
