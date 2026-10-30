package com.rutera.tacho.vu;

import java.util.List;

/** Transfer "technical data" (TREP 05h, 25h, 35h). */
public record VuTechnicalData(VuIdentification identification, List<VuSensor> pairedSensors,
                              List<VuSensor> externalGnssCouplings, List<VuCalibration> calibrations) {
}
