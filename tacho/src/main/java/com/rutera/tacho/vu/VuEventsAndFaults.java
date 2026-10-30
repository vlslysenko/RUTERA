package com.rutera.tacho.vu;

import java.util.List;

/** Transfer "events and faults" (TREP 03h, 23h, 33h). */
public record VuEventsAndFaults(List<VuEventFault> faults, List<VuEventFault> events,
                               VuOverSpeedingControl overSpeedingControl,
                               List<VuOverSpeedingEvent> overSpeedingEvents,
                               List<VuTimeAdjustment> timeAdjustments) {
}
