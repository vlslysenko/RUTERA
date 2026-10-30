package com.rutera.tacho.card;

import com.rutera.tacho.model.EventFaultTypes;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;

/** CardEventRecord or CardFaultRecord (24 bytes): EF Events_Data (0502h) and EF Faults_Data (0503h). */
public record CardEventFault(int type, Instant begin, Instant end, VehicleRegistration vehicle) {

    public String typeKey() {
        return EventFaultTypes.key(type);
    }
}
