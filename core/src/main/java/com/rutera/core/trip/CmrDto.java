package com.rutera.core.trip;

import com.rutera.common.domain.CmrDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** CMR consignment notes (contract of 2026-11-02); box numbers of the CMR form in the comments. */
public final class CmrDto {

    private CmrDto() {
    }

    /** A party of boxes 1, 2, 16 and 17: name, address (lines separated by line breaks), country, VAT id. */
    public record Party(@Size(max = 200) String name, @Size(max = 500) String address, String country,
                        @Size(max = 32) String vatId) {
    }

    /** Boxes 6-12: marks and numbers, number of packages, method of packing, nature of goods, statistical number,
     *  gross weight in kg, volume in m3. */
    public record Line(@Size(max = 100) String marks, Integer packages, @Size(max = 100) String packing,
                       @Size(max = 300) String nature, @Size(max = 32) String statNumber,
                       @Digits(integer = 7, fraction = 1) BigDecimal grossWeightKg,
                       @Digits(integer = 5, fraction = 3) BigDecimal volumeM3) {
    }

    public record Signature(@Size(max = 100) String name, @Size(max = 100) String place, LocalDate date) {
    }

    /** Boxes 22 (sender), 23 (carrier), 24 (consignee). */
    public record Signatures(@Valid Signature sender, @Valid Signature carrier, @Valid Signature consignee) {
    }

    public record CashOnDelivery(@Digits(integer = 12, fraction = 2) BigDecimal amount, String currency) {
    }

    public record NumberRef(Long id, String number) {
    }

    public record Cmr(Long id, String number, CmrDocument.Status status, NumberRef order, NumberRef trip,
                      Party sender, Long senderContractorId, Party consignee, Long consigneeContractorId,
                      Party carrier, List<Party> successiveCarriers, String deliveryPlace, String deliveryCountry,
                      String loadingPlace, String loadingCountry, LocalDate loadingDate, String documentsAttached,
                      List<Line> lines, String senderInstructions, CmrDocument.PaymentTerms paymentTerms,
                      String paymentInstructions, CashOnDelivery cashOnDelivery, String carrierReservations,
                      String specialAgreements, String establishedPlace, LocalDate establishedDate,
                      String vehiclePlates, Signatures signatures, String notes, boolean hasPdf, Instant issuedAt,
                      Instant createdAt, Instant updatedAt) {
    }

    public record Page(List<Cmr> items, long total) {
    }

    /** Body of {@code POST}/{@code PUT /app/cmr[/{id}]}: PUT is a full replacement (drafts only). */
    public record Input(
            @Size(max = 32) String number,
            Long orderId,
            Long tripId,
            @Valid Party sender,
            Long senderContractorId,
            @Valid Party consignee,
            Long consigneeContractorId,
            @Valid Party carrier,
            @Valid @Size(max = 5) List<Party> successiveCarriers,
            @Size(max = 200) String deliveryPlace,
            String deliveryCountry,
            @Size(max = 200) String loadingPlace,
            String loadingCountry,
            LocalDate loadingDate,
            @Size(max = 500) String documentsAttached,
            @Valid @Size(max = 30) List<Line> lines,
            @Size(max = 1000) String senderInstructions,
            String paymentTerms,
            @Size(max = 500) String paymentInstructions,
            @Valid CashOnDelivery cashOnDelivery,
            @Size(max = 1000) String carrierReservations,
            @Size(max = 1000) String specialAgreements,
            @Size(max = 100) String establishedPlace,
            LocalDate establishedDate,
            @Size(max = 64) String vehiclePlates,
            @Valid Signatures signatures,
            @Size(max = 2000) String notes) {
    }
}
