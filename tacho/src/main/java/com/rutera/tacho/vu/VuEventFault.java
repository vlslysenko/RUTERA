package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.EventFaultTypes;

import java.time.Instant;

/**
 * VuEventRecord or VuFaultRecord (also VuPowerSupplyInterruptionRecord of the second generation).
 *
 * @param purpose EventFaultRecordPurpose: 0 one of the 10 most recent, 1 longest of one of the last 10 days, 2 one
 *                of the 5 longest of the last 365 days, 3 last of one of the last 10 days, 4 most serious of one
 *                of the last 10 days, 5 one of the 5 most serious of the last 365 days, 6 first after the last
 *                calibration, 7 active or on-going
 * @param similarEvents number of similar events that day; null for faults
 */
public record VuEventFault(int type, int purpose, Instant begin, Instant end, CardNumber driverCardBegin,
                           CardNumber coDriverCardBegin, CardNumber driverCardEnd, CardNumber coDriverCardEnd,
                           Integer similarEvents) {

    public String typeKey() {
        return EventFaultTypes.key(type);
    }
}
