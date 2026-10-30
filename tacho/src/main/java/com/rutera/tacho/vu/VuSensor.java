package com.rutera.tacho.vu;

import com.rutera.tacho.model.ExtendedSerialNumber;

import java.time.Instant;

/** SensorPaired / SensorPairedRecord, or SensorExternalGNSSCoupledRecord: a paired motion sensor or GNSS facility. */
public record VuSensor(ExtendedSerialNumber serialNumber, String approvalNumber, Instant date) {
}
