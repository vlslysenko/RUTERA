package com.rutera.tacho;

import com.rutera.tacho.TachoFormatException.Reason;
import com.rutera.tacho.card.CardActivityDay;
import com.rutera.tacho.card.CardApplication;
import com.rutera.tacho.card.CardVehicleUse;
import com.rutera.tacho.card.DriverCardFile;
import com.rutera.tacho.model.Activity;
import com.rutera.tacho.model.ActivityPeriod;
import com.rutera.tacho.model.PlaceRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.rutera.tacho.CardFixtures.CARD_NUMBER;
import static com.rutera.tacho.DddWriter.t;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverCardParserTest {

    @Test
    void gen1CardIdentityHolderAndLicence() {
        DriverCardFile card = TachoParser.parseDriverCard(CardFixtures.gen1Card());

        assertThat(card.generation()).isEqualTo(Generation.GEN1);
        assertThat(card.tachographG2()).isNull();
        assertThat(card.warnings()).isEmpty();
        assertThat(card.icc().cardApprovalNumber()).isEqualTo("e5-0360");
        assertThat(card.icc().cardExtendedSerialNumber().monthYear()).isEqualTo("06/23");

        var id = card.identification();
        assertThat(id.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(id.driverIdentification()).isEqualTo("TST00000000001");
        assertThat(id.issuingNation().iso2()).isEqualTo("PL");
        assertThat(id.issuingAuthority()).isEqualTo("TEST AUTHORITY");
        assertThat(id.validityBegin()).isEqualTo(t("2023-06-17T00:00:00Z"));
        assertThat(id.expiryDate()).isEqualTo(t("2028-06-16T23:59:59Z"));

        assertThat(card.holder().surname()).isEqualTo("TESTOWY");
        assertThat(card.holder().firstNames()).isEqualTo("JAN");
        assertThat(card.holder().birthDate()).isEqualTo(LocalDate.of(1980, 1, 2));
        assertThat(card.holder().preferredLanguage()).isEqualTo("pl");
        assertThat(card.drivingLicence().number()).isEqualTo("TESTLIC0001");
        assertThat(card.lastCardDownload()).isEqualTo(t("2024-03-22T15:58:03Z"));
    }

    @Test
    void gen1ActivityBufferIsWalkedAcrossTheEnd() {
        CardApplication app = TachoParser.parseDriverCard(CardFixtures.gen1Card()).tachograph();

        List<CardActivityDay> days = app.activityDays();
        assertThat(days).extracting(CardActivityDay::date).containsExactly(
                LocalDate.of(2024, 3, 25), LocalDate.of(2024, 3, 26), LocalDate.of(2024, 3, 27));
        CardActivityDay wrapped = days.get(1);
        assertThat(wrapped.presenceCounter()).isEqualTo(2);
        assertThat(wrapped.distanceKm()).isEqualTo(100);
        assertThat(wrapped.changes()).hasSize(4);
        assertThat(wrapped.changes().get(3).isManualEntry()).isTrue();

        List<ActivityPeriod> periods = wrapped.periods();
        assertThat(periods).extracting(ActivityPeriod::activity).containsExactly(
                Activity.BREAK_REST, Activity.DRIVING, Activity.BREAK_REST, Activity.WORK);
        assertThat(periods.get(1).start()).isEqualTo(t("2024-03-26T06:00:00Z"));
        assertThat(periods.get(1).end()).isEqualTo(t("2024-03-26T15:00:00Z"));
        assertThat(periods.get(1).minutes()).isEqualTo(540);
        assertThat(periods.get(3).cardPresent()).isFalse();
        assertThat(periods.stream().mapToLong(ActivityPeriod::minutes).sum()).isEqualTo(1440);
    }

    @Test
    void gen1EventsVehiclesPlacesAndControls() {
        CardApplication app = TachoParser.parseDriverCard(CardFixtures.gen1Card()).tachograph();

        assertThat(app.events()).extracting(e -> e.typeKey()).containsExactly(
                "DRIVING_WITHOUT_CARD", "POWER_SUPPLY_INTERRUPTION");
        assertThat(app.events().get(1).vehicle().number()).isEqualTo("TEST123");
        assertThat(app.faults()).singleElement().satisfies(f -> assertThat(f.typeKey()).isEqualTo("SENSOR_FAULT"));

        assertThat(app.vehiclesUsed()).hasSize(2);
        CardVehicleUse first = app.vehiclesUsed().getFirst();
        assertThat(first.odometerBeginKm()).isEqualTo(1000);
        assertThat(first.odometerEndKm()).isEqualTo(1200);
        assertThat(first.registration().nation().iso2()).isEqualTo("PL");
        assertThat(first.vin()).isNull();

        assertThat(app.places()).extracting(PlaceRecord::isBegin).containsExactly(true, false);
        assertThat(app.places().get(1).country().iso2()).isEqualTo("DE");
        assertThat(app.places().get(1).country().tachoCode()).isEqualTo("D");
        assertThat(app.places().get(1).gnss()).isNull();

        assertThat(app.currentUse().vehicle().number()).isEqualTo("TEST123");
        assertThat(app.lastControl().controlCard().number()).isEqualTo("CTRL000000000001");
        assertThat(app.lastControl().controlCard().equipmentType()).isEqualTo(3);
        assertThat(app.specificConditions()).singleElement()
                .satisfies(c -> assertThat(c.typeKey()).isEqualTo("FERRY_TRAIN_BEGIN"));
    }

    @Test
    void gen1Summary() {
        TachoFileSummary s = TachoParser.parse(CardFixtures.gen1Card()).summary();

        assertThat(s.kind()).isEqualTo(FileKind.DRIVER_CARD);
        assertThat(s.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(s.cardIssuingCountry()).isEqualTo("PL");
        assertThat(s.cardExpiry()).isEqualTo(LocalDate.of(2028, 6, 16));
        assertThat(s.periodFrom()).isEqualTo(LocalDate.of(2024, 3, 25));
        assertThat(s.periodTo()).isEqualTo(LocalDate.of(2024, 3, 27));
        // later than EF Card_Download (22.03): the last activity change on 27.03 at 07:00
        assertThat(s.downloadedAt()).isEqualTo(t("2024-03-27T07:00:00Z"));
        assertThat(s.vin()).isNull();
    }

    @Test
    void gen2CardHasBothApplicationsAndMergesThem() {
        DriverCardFile card = TachoParser.parseDriverCard(CardFixtures.gen2Card(false));

        assertThat(card.generation()).isEqualTo(Generation.GEN2);
        assertThat(card.warnings()).isEmpty();
        assertThat(card.tachograph().generation()).isEqualTo(Generation.GEN1);
        CardApplication g2 = card.tachographG2();
        assertThat(g2.generation()).isEqualTo(Generation.GEN2);
        assertThat(g2.applicationIdentification().gnssAdRecords()).isEqualTo(2);
        assertThat(card.primary()).isSameAs(g2);

        // days 25 and 27 from GEN1 (27 has more data there), day 26 from GEN2
        List<CardActivityDay> days = card.activityDays();
        assertThat(days).extracting(CardActivityDay::date).containsExactly(
                LocalDate.of(2024, 3, 25), LocalDate.of(2024, 3, 26), LocalDate.of(2024, 3, 27));
        assertThat(days.get(1).changes()).hasSize(4);
        assertThat(days.get(1).changes().get(1).activity()).isEqualTo(Activity.WORK);

        // the second vehicle use is in both applications: once, with the VIN of GEN2
        assertThat(card.vehiclesUsed()).hasSize(2);
        assertThat(card.vehiclesUsed().get(1).vin()).isEqualTo("TESTVIN0000000001");

        // place at 25.03 06:00 in both: the GEN2 one with GNSS wins; 25.03 16:00 only GEN1; 26.03 only GEN2
        List<PlaceRecord> places = card.places();
        assertThat(places).hasSize(3);
        assertThat(places.getFirst().gnss().latitude()).isEqualTo(52.223333);
        assertThat(places.getFirst().gnss().longitude()).isEqualTo(21.008333);
        assertThat(places.get(2).gnss().latitude()).isEqualTo(-33.76);
        assertThat(places.get(2).gnss().longitude()).isEqualTo(-70.67);

        assertThat(card.events()).extracting(e -> e.typeKey()).containsExactly(
                "DRIVING_WITHOUT_CARD", "POWER_SUPPLY_INTERRUPTION", "VEHICLE_MOTION_CONFLICT");
        assertThat(card.specificConditions()).hasSize(2);

        assertThat(g2.vehicleUnitsUsed()).singleElement().satisfies(u -> {
            assertThat(u.manufacturerCode()).isEqualTo(0xA1);
            assertThat(u.softwareVersion()).isEqualTo("0300");
        });
        assertThat(g2.gnssAccumulatedDriving()).singleElement().satisfies(g -> {
            assertThat(g.odometerKm()).isEqualTo(1250);
            assertThat(g.place().accuracy()).isEqualTo(5);
            assertThat(g.place().authenticationStatus()).isNull();
        });
        assertThat(g2.borderCrossings()).isEmpty();
    }

    @Test
    void gen2Version2CardHasBorderCrossingsAndLoads() {
        DriverCardFile card = TachoParser.parseDriverCard(CardFixtures.gen2Card(true));

        assertThat(card.generation()).isEqualTo(Generation.GEN2V2);
        assertThat(card.warnings()).isEmpty();
        CardApplication g2 = card.tachographG2();
        assertThat(g2.applicationIdentification().borderCrossingRecords()).isEqualTo(2);
        assertThat(g2.borderCrossings()).singleElement().satisfies(b -> {
            assertThat(b.countryLeft().iso2()).isEqualTo("PL");
            assertThat(b.countryEntered().iso2()).isEqualTo("DE");
            assertThat(b.place().authenticationStatus()).isEqualTo(1);
            assertThat(b.place().timestamp()).isEqualTo(t("2024-03-26T11:00:00Z"));
            assertThat(b.odometerKm()).isEqualTo(1280);
        });
        assertThat(g2.loadUnloadOperations()).singleElement().satisfies(l -> {
            assertThat(l.operationType()).isEqualTo(1);
            assertThat(l.odometerKm()).isEqualTo(1240);
        });
        assertThat(g2.loadTypeEntries()).singleElement().satisfies(l -> assertThat(l.loadType()).isEqualTo(1));
        assertThat(card.summary().generation()).isEqualTo(Generation.GEN2V2);
    }

    @Test
    void damagedActivityGivesAWarningAndTheRestIsRead() {
        DriverCardFile card = TachoParser.parseDriverCard(CardFixtures.cardWithBrokenActivity());

        assertThat(card.warnings()).singleElement().asString().contains("Driver_Activity_Data");
        assertThat(card.activityDays()).isEmpty();
        assertThat(card.identification().cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(card.vehiclesUsed()).hasSize(2);
    }

    @Test
    void companyCardIsNotSupported() {
        assertThatThrownBy(() -> TachoParser.parse(CardFixtures.companyCard()))
                .isInstanceOfSatisfying(TachoFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED));
    }
}
