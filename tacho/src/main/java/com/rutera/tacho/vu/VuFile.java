package com.rutera.tacho.vu;

import com.rutera.tacho.FileKind;
import com.rutera.tacho.Generation;
import com.rutera.tacho.TachoFile;
import com.rutera.tacho.TachoFileSummary;
import com.rutera.tacho.model.VehicleRegistration;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * A vehicle unit download: the transfers the download tool requested, in file order. Activity days are sorted by
 * date. Parts that were not downloaded are null (overview, events and faults, technical data) or empty.
 */
public record VuFile(Generation generation, VuOverview overview, List<VuActivityDay> activityDays,
                     VuEventsAndFaults eventsAndFaults, List<VuSpeedBlock> detailedSpeed,
                     VuTechnicalData technicalData, List<String> warnings) implements TachoFile {

    @Override
    public FileKind kind() {
        return FileKind.VEHICLE_UNIT;
    }

    /** VIN from the overview, otherwise from the latest calibration. */
    public String vin() {
        if (overview != null && overview.vin() != null) {
            return overview.vin();
        }
        VuCalibration c = latestCalibration();
        return c == null ? null : c.vin();
    }

    /** Plate from the overview, otherwise from the latest calibration. */
    public VehicleRegistration registration() {
        if (overview != null && overview.registration() != null) {
            return overview.registration();
        }
        VuCalibration c = latestCalibration();
        return c == null ? null : c.registration();
    }

    private VuCalibration latestCalibration() {
        if (technicalData == null) {
            return null;
        }
        return technicalData.calibrations().stream()
                .filter(c -> c.newTime() != null)
                .max(Comparator.comparing(VuCalibration::newTime))
                .orElse(null);
    }

    @Override
    public TachoFileSummary summary() {
        LocalDate from = activityDays.isEmpty() ? null : activityDays.getFirst().date();
        LocalDate to = activityDays.isEmpty() ? null : activityDays.getLast().date();
        if (from == null && overview != null && overview.downloadablePeriodBegin() != null) {
            from = LocalDate.ofInstant(overview.downloadablePeriodBegin(), ZoneOffset.UTC);
            to = overview.downloadablePeriodEnd() == null ? null
                    : LocalDate.ofInstant(overview.downloadablePeriodEnd(), ZoneOffset.UTC);
        }
        VehicleRegistration reg = registration();
        String plateCountry = reg == null || reg.nation().isEmpty() ? null : reg.nation().display();
        return new TachoFileSummary(FileKind.VEHICLE_UNIT, generation, null, null, null, vin(),
                reg == null ? null : reg.number(), plateCountry, from, to,
                overview == null ? null : overview.currentTime());
    }
}
