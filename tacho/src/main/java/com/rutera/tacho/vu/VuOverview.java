package com.rutera.tacho.vu;

import com.rutera.tacho.Generation;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.Instant;
import java.util.List;

/**
 * Transfer "overview" (TREP 01h, 21h, 31h).
 *
 * @param registration plate and registering state; second generation version 1 records the plate without the
 *                     state (nation code 0)
 * @param currentTime VU time at the moment of the download
 * @param driverSlotCardType card type in slot 1 at the download (0 none, 1 driver, 2 workshop, 3 control, 4
 *                           company); {@code coDriverSlotCardType} the same for slot 2
 */
public record VuOverview(Generation generation, String vin, VehicleRegistration registration, Instant currentTime,
                         Instant downloadablePeriodBegin, Instant downloadablePeriodEnd, int driverSlotCardType,
                         int coDriverSlotCardType, VuDownloadActivity previousDownload,
                         List<VuCompanyLock> companyLocks, List<VuControlActivity> controls) {
}
