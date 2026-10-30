package com.rutera.core.fleet;

import com.rutera.common.domain.VehicleDocument;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A document of a vehicle ({@code VehicleDocument} in the frontend contract). */
public record VehicleDocumentDto(
        Long id,
        VehicleDocument.Kind kind,
        String number,
        LocalDate validFrom,
        LocalDate validTo,
        BigDecimal cost,
        String currency,
        int remindDays) {

    public static VehicleDocumentDto of(VehicleDocument d) {
        return new VehicleDocumentDto(d.getId(), d.getKind(), d.getNumber(), d.getValidFrom(), d.getValidTo(),
                d.getCost(), d.getCurrency(), d.getRemindDays());
    }
}
