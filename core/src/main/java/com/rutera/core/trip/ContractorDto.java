package com.rutera.core.trip;

import com.rutera.common.domain.Contractor;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** A contractor as the screens show it ({@code Contractor} in the trips/orders contract). */
public record ContractorDto(
        Long id,
        String name,
        String vatId,
        String country,
        String address,
        String phone,
        List<String> invoiceEmails,
        Integer paymentDays,
        String lang,
        boolean isCustomer,
        boolean isCarrier,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public static ContractorDto of(Contractor c, List<String> invoiceEmails) {
        return new ContractorDto(c.getId(), c.getName(), c.getVatId(), c.getCountry(), c.getAddress(), c.getPhone(),
                invoiceEmails, c.getPaymentDays(), c.getLang(), c.isCustomer(), c.isCarrier(), c.getNotes(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    /** {@code {id, name}} of a contractor inside orders and trips. */
    public record Ref(Long id, String name) {

        public static Ref of(Contractor c) {
            return c == null ? null : new Ref(c.getId(), c.getName());
        }
    }

    /**
     * Body of {@code POST} and {@code PUT /app/contractors[/{id}]} (PUT is a full replacement). {@code isCustomer}
     * defaults to true, {@code isCarrier} to false.
     */
    public record Input(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 40) String vatId,
            @NotBlank @Size(max = 5) String country,
            @Size(max = 500) String address,
            @Size(max = 40) String phone,
            @Size(max = 5) List<@Size(max = 254) String> invoiceEmails,
            @Min(0) @Max(365) Integer paymentDays,
            @Size(max = 5) String lang,
            Boolean isCustomer,
            Boolean isCarrier,
            @Size(max = 2000) String notes) {
    }
}
