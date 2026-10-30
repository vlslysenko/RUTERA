package com.rutera.tacho.vu;

import com.rutera.tacho.model.CardNumber;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;

/**
 * VuCalibrationRecord (the fields common to all generations).
 *
 * @param purpose CalibrationPurpose: 1 activation, 2 first installation, 3 installation, 4 periodic inspection,
 *                5 entry of the registration number by the company, 6 time adjustment without calibration, 7 entry
 *                of the load type (GEN2V2), 8 entry of the country (GEN2V2)
 * @param wConstant characteristic coefficient of the vehicle, impulses per km
 * @param kConstant constant of the recording equipment, impulses per km
 * @param lTyreCircumference effective tyre circumference in 1/8 mm
 */
public record VuCalibration(int purpose, String workshopName, String workshopAddress, CardNumber workshopCard,
                            Instant workshopCardExpiry, String vin, VehicleRegistration registration, int wConstant,
                            int kConstant, int lTyreCircumference, String tyreSize, int authorisedSpeed,
                            Integer oldOdometerKm, Integer newOdometerKm, Instant oldTime, Instant newTime,
                            Instant nextCalibrationDate) {
}
