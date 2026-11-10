package com.rutera.core.fleet.vin;

import com.rutera.core.fleet.vin.VinDecodeDto.Suggestion;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline VIN decoding: WMI, model year, check digit, maker patterns and the suggestions built on them. */
class VinDecoderTest {

    private final VinDecoder decoder =
            new VinDecoder(Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));

    private static Optional<Suggestion> field(VinDecodeDto dto, String field) {
        return dto.suggestions().stream().filter(s -> s.field().equals(field)).findFirst();
    }

    @Test
    void catalogueIsLoaded() {
        assertThat(decoder.generations()).hasSizeGreaterThan(100);
        assertThat(decoder.generations()).allSatisfy(g -> assertThat(g.euro()).allMatch(e -> e.startsWith("EURO_")));
    }

    @Test
    void scaniaSeriesAndWheelFormulaWithRegistrationYear() {
        VinDecodeDto d = decoder.decode("ys2 r4x2 0005663528", 2022);
        assertThat(d.status()).isEqualTo("OK");
        assertThat(d.vin()).isEqualTo("YS2R4X20005663528");
        assertThat(d.manufacturer()).isEqualTo("Scania");
        assertThat(d.country()).isEqualTo("SE");
        assertThat(d.yearNotEncoded()).isTrue();
        assertThat(d.modelYear()).isNull();
        assertThat(d.year()).isEqualTo(2022);
        assertThat(d.yearSource()).isEqualTo("REGISTRATION");
        assertThat(d.wheels()).isEqualTo("4X2");
        assertThat(field(d, "make")).hasValueSatisfying(s -> {
            assertThat(s.value()).isEqualTo("Scania");
            assertThat(s.confidence()).isEqualTo("HIGH");
        });
        assertThat(field(d, "model")).hasValueSatisfying(s -> {
            assertThat(s.value()).isEqualTo("R");
            assertThat(s.basis()).isEqualTo("VDS");
        });
        assertThat(field(d, "axles")).hasValueSatisfying(s -> assertThat(s.value()).isEqualTo("2"));
        assertThat(field(d, "euroClass")).hasValueSatisfying(s -> {
            assertThat(s.value()).isEqualTo("EURO_6");
            assertThat(s.confidence()).isEqualTo("MEDIUM");
            assertThat(s.basis()).isEqualTo("YEAR");
        });
        // The R series of 2022 also exists with gas engines: diesel is only the likely value.
        assertThat(field(d, "fuelType")).hasValueSatisfying(s -> {
            assertThat(s.value()).isEqualTo("DIESEL");
            assertThat(s.options()).containsExactly("DIESEL", "LNG", "CNG");
            assertThat(s.confidence()).isEqualTo("LOW");
        });
        assertThat(d.engines()).contains("Scania DC13");
    }

    @Test
    void mercedesModelSeriesWithoutYearLeavesTheChoiceOpen() {
        VinDecodeDto d = decoder.decode("W1T96342320564003", null);
        assertThat(d.manufacturer()).isEqualTo("Mercedes-Benz");
        assertThat(d.country()).isEqualTo("DE");
        assertThat(d.yearNotEncoded()).isTrue();
        assertThat(d.year()).isNull();
        assertThat(field(d, "model")).hasValueSatisfying(s -> {
            assertThat(s.value()).isNull();
            assertThat(s.options()).containsExactly("Actros", "Arocs", "Antos");
            assertThat(s.confidence()).isEqualTo("LOW");
        });
        assertThat(field(d, "kind")).hasValueSatisfying(s -> assertThat(s.value()).isEqualTo("TRACTOR"));
        assertThat(field(d, "euroClass")).isEmpty();
        assertThat(d.engines()).isEmpty();
    }

    @Test
    void mercedesVanIsNotTreatedAsHeavyTruck() {
        VinDecodeDto d = decoder.decode("WDB9066331S123456", 2015);
        assertThat(field(d, "model")).hasValueSatisfying(s -> assertThat(s.value()).isEqualTo("Sprinter"));
        assertThat(field(d, "kind")).hasValueSatisfying(s -> {
            assertThat(s.value()).isEqualTo("VAN");
            assertThat(s.confidence()).isEqualTo("MEDIUM");
        });
        assertThat(field(d, "euroClass")).isEmpty();
        assertThat(field(d, "fuelType")).isEmpty();
    }

    @Test
    void volvoModelYearDrivesCatalogueSuggestions() {
        VinDecodeDto d = decoder.decode("YV2JG10A9BB582658", null);
        assertThat(d.manufacturer()).isEqualTo("Volvo");
        assertThat(d.modelYear()).isEqualTo(2011);
        assertThat(d.modelYearConfidence()).isEqualTo("MEDIUM");
        assertThat(d.year()).isEqualTo(2011);
        assertThat(d.yearSource()).isEqualTo("MODEL_YEAR");
        assertThat(field(d, "model")).hasValueSatisfying(s -> {
            assertThat(s.value()).isNull();
            assertThat(s.options()).contains("FH", "FM");
            assertThat(s.basis()).isEqualTo("MODELS");
        });
        assertThat(field(d, "euroClass")).hasValueSatisfying(s -> assertThat(s.value()).isEqualTo("EURO_5"));
    }

    @Test
    void registrationYearWinsOverModelYear() {
        VinDecodeDto d = decoder.decode("YV2JG10A9BB582658", 2014);
        assertThat(d.modelYear()).isEqualTo(2011);
        assertThat(d.year()).isEqualTo(2014);
        assertThat(d.yearSource()).isEqualTo("REGISTRATION");
        assertThat(field(d, "euroClass")).hasValueSatisfying(s -> assertThat(s.value()).isEqualTo("EURO_6"));
    }

    @Test
    void northAmericanVinHasMandatoryCheckDigitAndModelYear() {
        VinDecodeDto d = decoder.decode("1M8GDM9AXKP042788", null);
        assertThat(d.country()).isEqualTo("US");
        assertThat(d.checkDigitRequired()).isTrue();
        assertThat(d.checkDigit()).isEqualTo("VALID");
        assertThat(d.modelYear()).isEqualTo(2019);
        assertThat(d.modelYearConfidence()).isEqualTo("HIGH");
        assertThat(d.manufacturer()).isNull();
        assertThat(d.suggestions()).isEmpty();

        VinDecodeDto typo = decoder.decode("1M8GDM9AXKP042789", null);
        assertThat(typo.checkDigit()).isEqualTo("INVALID");
    }

    @Test
    void trailerMakerSuggestsTrailerKindOnly() {
        VinDecodeDto d = decoder.decode("WSM00000003123456", 2020);
        assertThat(d.manufacturer()).isEqualTo("Schmitz Cargobull");
        assertThat(field(d, "kind")).hasValueSatisfying(s -> {
            assertThat(s.value()).isEqualTo("SEMI_TRAILER");
            assertThat(s.options()).containsExactly("SEMI_TRAILER", "TRAILER");
            assertThat(s.confidence()).isEqualTo("LOW");
        });
        assertThat(field(d, "euroClass")).isEmpty();
        assertThat(field(d, "fuelType")).isEmpty();
    }

    @Test
    void unknownMakerStillGivesCountry() {
        VinDecodeDto d = decoder.decode("SUD12345678901234", null);
        assertThat(d.status()).isEqualTo("OK");
        assertThat(d.manufacturer()).isNull();
        assertThat(d.country()).isEqualTo("PL");
        assertThat(d.suggestions()).isEmpty();
    }

    @Test
    void invalidVins() {
        assertThat(decoder.decode("YS2R4X2000566352", null).status()).isEqualTo("INVALID_LENGTH");
        assertThat(decoder.decode("", null).status()).isEqualTo("INVALID_LENGTH");
        assertThat(decoder.decode(null, null).status()).isEqualTo("INVALID_LENGTH");
        VinDecodeDto o = decoder.decode("YS2R4X2000566352O", null);
        assertThat(o.status()).isEqualTo("INVALID_CHARS");
        assertThat(o.manufacturer()).isNull();
        assertThat(o.suggestions()).isEmpty();
    }

    @Test
    void euroPresumptionByYear() {
        assertThat(VinDecoder.presumed(2000)).isEmpty();
        assertThat(VinDecoder.presumed(2003)).containsExactly("EURO_3");
        assertThat(VinDecoder.presumed(2009)).containsExactly("EURO_4", "EURO_5");
        assertThat(VinDecoder.presumed(2013)).containsExactly("EURO_5");
        assertThat(VinDecoder.presumed(2014)).containsExactly("EURO_6");

        Suggestion change = VinDecoder.euro(2009, Set.of(), Set.of("DIESEL"));
        assertThat(change.value()).isEqualTo("EURO_4");
        assertThat(change.options()).containsExactly("EURO_4", "EURO_5");
        assertThat(change.confidence()).isEqualTo("LOW");
        assertThat(VinDecoder.euro(2020, Set.of(), Set.of("ELECTRIC"))).isNull();
    }

    @Test
    void modelYearCycleAndCountries() {
        assertThat(VinDecoder.modelYear('B', 2027)).isEqualTo(2011);
        assertThat(VinDecoder.modelYear('T', 2027)).isEqualTo(2026);
        assertThat(VinDecoder.modelYear('V', 2027)).isEqualTo(2027);
        assertThat(VinDecoder.modelYear('W', 2027)).isEqualTo(1998);
        assertThat(VinDecoder.modelYear('0', 2027)).isNull();
        assertThat(VinDecoder.country("XLRTE47MS0E123456")).isEqualTo("NL");
        assertThat(VinDecoder.country("VF6MF000000123456")).isEqualTo("FR");
        assertThat(VinDecoder.country("ZCFC35A0000123456")).isEqualTo("IT");
        assertThat(VinDecoder.country("TMBJJ7NE000000000")).isEqualTo("CZ");
    }
}
