package com.rutera.tacho.card;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;

/**
 * EF Control_Activity_Data (0508h): the last roadside or premises control.
 *
 * @param controlType bits 'cvdt0000': card downloaded, VU downloaded, printing, display
 */
public record CardControlActivity(int controlType, Instant time, CardNumber controlCard,
                                  VehicleRegistration vehicle, Instant downloadPeriodBegin,
                                  Instant downloadPeriodEnd) {
}
