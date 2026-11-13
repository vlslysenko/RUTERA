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
     * violation rule and infringementCode its template code; minutes of the infringement (actual, limit, exceeded);
     * the Article 12 claim and what this infringement allows; docLang the language of its PDF.
     */
    public record Explanation(Long id, String status, String source, Ref driver, VehicleRef vehicle, Long violationId,
                              String ruleCode, String infringementCode, Instant startedAt, Instant endedAt,
                              Integer actualMin, Integer limitMin, Integer exceededMin, String reason,
                              String reasonText, String place, String art12, String art12Place, String art12BreakFrom,
                              String art12BreakTo, Boolean printoutNote, Instant printoutNoteAt,
                              Art12Allowed art12Allowed, String docLang, Instant signedAt, Ref signedBy,
                              Instant cancelledAt, Instant createdAt, Ref createdBy, boolean hasPdf) {
    }

    /** Which Article 12 claims the infringement allows: first paragraph (safety), second / third (home). */
    public record Art12Allowed(boolean safety, boolean home) {
    }

    /** A reason for an infringement: its Article 12 options with it and whether the driver's text is required. */
    public record ReasonOption(String code, boolean art12Safety, boolean art12Home, boolean textRequired) {
    }

    /** The note to copy by hand onto the tachograph printout, in the document's language. */
    public record PrintoutNote(String lang, String instruction, String text) {
    }

    public record Page(List<Explanation> items, long total) {
    }

    /** Body of {@code POST /app/explanations}: a violation, or driver, rule and time by hand. */
    public record CreateInput(Long violationId, Long driverId, Long vehicleId, String ruleCode, Instant startedAt,
                              Instant endedAt, Integer exceededMin) {
    }

    /**
     * Body of {@code POST /app/explanations/{id}/sign}: signature is a PNG data URL; art12 NONE | SAFETY | HOME_1H |
     * HOME_2H with its place (stopping place or destination), the break before a 2 h extension (HH:mm) and whether
     * the handwritten note on the printout was made.
     */
    public record SignInput(String reason, @Size(max = 1000) String reasonText, @Size(max = 100) String place,
                            String signature, String art12, @Size(max = 100) String art12Place,
                            String art12BreakFrom, String art12BreakTo, Boolean printoutNote,
                            Instant printoutNoteAt) {
    }
}
