package com.rutera.core.fleet.vin;

import java.util.List;

/**
 * Result of {@code GET /app/fleet/vin}: what a VIN tells about a vehicle, decoded offline.
 *
 * @param vin                 the VIN without spaces and hyphens, upper-cased
 * @param status              {@code OK}, {@code INVALID_LENGTH} (not 17 characters) or {@code INVALID_CHARS}
 *                            (other than A-Z and 0-9, or one of I, O, Q); only {@code vin} is set otherwise
 * @param wmi                 characters 1-3, the world manufacturer identifier
 * @param manufacturer        maker known from the WMI, null when the WMI is not in the table
 * @param country             ISO 3166 alpha-2 code of the country of manufacture, null when unknown
 * @param checkDigit          {@code VALID} or {@code INVALID}: character 9 against the ISO 3779 check digit
 * @param checkDigitRequired  true for North American VINs, where the check digit is mandatory; elsewhere a
 *                            mismatch usually means the maker does not use it
 * @param modelYear           model year from character 10, null when not decoded
 * @param modelYearConfidence {@code HIGH}, {@code MEDIUM} or {@code LOW}; null without {@code modelYear}
 * @param yearNotEncoded      true when the maker is known not to put the year into the VIN
 * @param year                year the Euro class, fuel and model suggestions are based on, null when none
 * @param yearSource          {@code REGISTRATION} (given first registration year) or {@code MODEL_YEAR}
 * @param wheels              wheel formula written in the VIN (Scania), e.g. {@code 6X2}, null otherwise
 * @param suggestions         field values for the vehicle form, each with its confidence
 * @param engines             engine families known for the suggested model in {@code year} (information only)
 */
public record VinDecodeDto(
        String vin,
        String status,
        String wmi,
        String manufacturer,
        String country,
        String checkDigit,
        boolean checkDigitRequired,
        Integer modelYear,
        String modelYearConfidence,
        boolean yearNotEncoded,
        Integer year,
        String yearSource,
        String wheels,
        List<Suggestion> suggestions,
        List<String> engines) {

    static VinDecodeDto invalid(String vin, String status) {
        return new VinDecodeDto(vin, status, null, null, null, null, false, null, null, false, null, null, null,
                List.of(), List.of());
    }

    /**
     * A value for one field of the vehicle form.
     *
     * @param field      {@code make}, {@code model}, {@code kind}, {@code axles}, {@code fuelType} or {@code euroClass}
     * @param value      the suggested value (enum name for kind, fuel and Euro class), null when the user has to
     *                   pick one of {@code options}
     * @param options    other possible values, including {@code value}; empty when there is only one
     * @param confidence {@code HIGH} (written in the VIN), {@code MEDIUM} (likely) or {@code LOW} (a guess)
     * @param basis      where it comes from: {@code WMI} (characters 1-3), {@code VDS} (characters 4-8),
     *                   {@code WHEELS} (wheel formula in the VIN), {@code YEAR} (presumption by year) or
     *                   {@code MODELS} (catalogue of the maker's models)
     */
    public record Suggestion(String field, String value, List<String> options, String confidence, String basis) {
    }
}
