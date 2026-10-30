package com.rutera.core.driver;

import com.rutera.common.domain.DriverDocument;

import java.time.LocalDate;

/** A document of a driver ({@code DriverDocument} in the frontend contract). */
public record DriverDocumentDto(
        Long id,
        DriverDocument.Kind kind,
        String number,
        LocalDate validFrom,
        LocalDate validTo,
        int remindDays) {

    public static DriverDocumentDto of(DriverDocument d) {
        return new DriverDocumentDto(d.getId(), d.getKind(), d.getNumber(), d.getValidFrom(), d.getValidTo(),
                d.getRemindDays());
    }
}
