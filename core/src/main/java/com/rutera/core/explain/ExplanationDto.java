package com.rutera.core.explain;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Driver explanations ({@code /app/explanations}). */
public final class ExplanationDto {

    private ExplanationDto() {
    }

    public record Ref(Long id, String name) {
    }

    public record VehicleRef(Long id, String plate) {
    }

    /**
     * An explanation: status PENDING | SIGNED | CANCELLED, source AUTO (from the import) | MANUAL; ruleCode a
     * violation rule; minutes of the infringement (actual, limit, exceeded); docLang the language of its PDF.
     */
    public record Explanation(Long id, String status, String source, Ref driver, VehicleRef vehicle, Long violationId,
                              String ruleCode, Instant startedAt, Instant endedAt, Integer actualMin, Integer limitMin,
                              Integer exceededMin, String reason, String reasonText, String place, String docLang,
                              Instant signedAt, Ref signedBy, Instant cancelledAt, Instant createdAt, Ref createdBy,
                              boolean hasPdf) {
    }

    public record Page(List<Explanation> items, long total) {
    }

    /** Body of {@code POST /app/explanations}: a violation, or driver, rule and time by hand. */
    public record CreateInput(Long violationId, Long driverId, Long vehicleId, String ruleCode, Instant startedAt,
                              Instant endedAt, Integer exceededMin) {
    }

    /** Body of {@code POST /app/explanations/{id}/sign}: signature is a PNG data URL. */
    public record SignInput(String reason, @Size(max = 1000) String reasonText, @Size(max = 100) String place,
                            String signature) {
    }
}
