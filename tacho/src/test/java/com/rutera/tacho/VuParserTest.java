package com.rutera.tacho;

import com.rutera.tacho.TachoFormatException.Reason;
import com.rutera.tacho.model.Activity;
import com.rutera.tacho.model.ActivityPeriod;
import com.rutera.tacho.model.Slot;
import com.rutera.tacho.vu.VuActivityDay;
import com.rutera.tacho.vu.VuCalibration;
import com.rutera.tacho.vu.VuCardInsertion;
import com.rutera.tacho.vu.VuFile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static com.rutera.tacho.CardFixtures.CARD_NUMBER;
import static com.rutera.tacho.DddWriter.t;
import static com.rutera.tacho.VuFixtures.VIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VuParserTest {

    @Test
    void gen1Overview() {
        VuFile vu = TachoParser.parseVehicleUnit(VuFixtures.gen1Vu());

        assertThat(vu.generation()).isEqualTo(Generation.GEN1);
        assertThat(vu.warnings()).isEmpty();
        var o = vu.overview();
        assertThat(o.vin()).isEqualTo(VIN);
        assertThat(o.registration().number()).isEqualTo("TEST123");
        assertThat(o.registration().nation().iso2()).isEqualTo("PL");
        assertThat(o.currentTime()).isEqualTo(t("2024-04-01T09:13:11Z"));
        assertThat(o.downloadablePeriodBegin()).isEqualTo(t("2024-03-01T00:00:00Z"));
        assertThat(o.driverSlotCardType()).isEqualTo(1);
        assertThat(o.coDriverSlotCardType()).isZero();
        assertThat(o.previousDownload().downloadingTime()).isEqualTo(t("2024-03-22T13:35:14Z"));
        assertThat(o.previousDownload().companyOrWorkshopName()).isEqualTo("TEST FIRMA");
        assertThat(o.companyLocks()).singleElement().satisfies(l -> {
            assertThat(l.lockOut()).isNull();
            assertThat(l.companyCard().equipmentType()).isEqualTo(4);
        });
        assertThat(o.controls()).singleElement()
                .satisfies(c -> assertThat(c.controlCard().issuingNation().iso2()).isEqualTo("DE"));
    }

    @Test
    void gen1ActivitiesAreSortedAndCoverBothSlots() {
        VuFile vu = TachoParser.parseVehicleUnit(VuFixtures.gen1Vu());

        assertThat(vu.activityDays()).extracting(VuActivityDay::date)
                .containsExactly(LocalDate.of(2024, 3, 26), LocalDate.of(2024, 3, 27));
        VuActivityDay day = vu.activityDays().getFirst();
        assertThat(day.odometerMidnightKm()).isEqualTo(1200);

        VuCardInsertion iw = day.cardInsertions().getFirst();
        assertThat(iw.card().number()).isEqualTo(CARD_NUMBER);
        assertThat(iw.holderSurname()).isEqualTo("TESTOWY");
        assertThat(iw.slot()).isEqualTo(Slot.DRIVER);
        assertThat(iw.insertionTime()).isEqualTo(t("2024-03-26T05:55:00Z"));
        assertThat(iw.withdrawalTime()).isEqualTo(t("2024-03-26T15:05:00Z"));
        assertThat(iw.odometerAtWithdrawalKm()).isEqualTo(1300);
        assertThat(iw.previousVehicle().number()).isEqualTo("PREV1");

        List<ActivityPeriod> periods = day.periods();
        assertThat(periods.stream().filter(p -> p.slot() == Slot.DRIVER).mapToLong(ActivityPeriod::minutes).sum())
                .isEqualTo(1440);
        assertThat(periods.stream().filter(p -> p.slot() == Slot.CO_DRIVER)).singleElement()
                .satisfies(p -> assertThat(p.cardPresent()).isFalse());
        assertThat(periods.stream().filter(p -> p.activity() == Activity.DRIVING)).singleElement()
                .satisfies(p -> assertThat(p.minutes()).isEqualTo(520));

        assertThat(day.places()).singleElement().satisfies(p -> {
            assertThat(p.card().number()).isEqualTo(CARD_NUMBER);
            assertThat(p.place().odometerKm()).isEqualTo(1200);
        });
    }

    @Test
    void gen1EventsSpeedAndTechnicalData() {
        VuFile vu = TachoParser.parseVehicleUnit(VuFixtures.gen1Vu());

        var e = vu.eventsAndFaults();
        assertThat(e.faults()).singleElement().satisfies(f -> {
            assertThat(f.typeKey()).isEqualTo("SENSOR_FAULT");
            assertThat(f.driverCardBegin()).isNull();
            assertThat(f.similarEvents()).isNull();
        });
        assertThat(e.events()).singleElement().satisfies(ev -> {
            assertThat(ev.typeKey()).isEqualTo("POWER_SUPPLY_INTERRUPTION");
            assertThat(ev.driverCardBegin().number()).isEqualTo(CARD_NUMBER);
            assertThat(ev.similarEvents()).isEqualTo(2);
        });
        assertThat(e.overSpeedingControl().lastControl()).isEqualTo(t("2024-01-19T07:26:43Z"));
        assertThat(e.overSpeedingEvents()).singleElement().satisfies(o -> {
            assertThat(o.maxSpeed()).isEqualTo(98);
            assertThat(o.averageSpeed()).isEqualTo(91);
        });
        assertThat(e.timeAdjustments()).isEmpty();

        assertThat(vu.detailedSpeed()).singleElement().satisfies(b -> {
            assertThat(b.begin()).isEqualTo(t("2024-03-26T10:00:00Z"));
            assertThat(b.speeds()[59]).isEqualTo(59);
        });

        var td = vu.technicalData();
        assertThat(td.identification().manufacturerName()).isEqualTo("TEST VU MAKER");
        assertThat(td.identification().approvalNumber()).isEqualTo("e1-84");
        assertThat(td.identification().serialNumber().serialNumber()).isEqualTo(408128);
        assertThat(td.identification().vuGeneration()).isNull();
        assertThat(td.pairedSensors()).singleElement()
                .satisfies(s -> assertThat(s.approvalNumber()).isEqualTo("e1-85"));
        VuCalibration c = td.calibrations().getFirst();
        assertThat(c.purpose()).isEqualTo(4);
        assertThat(c.workshopCard().number()).isEqualTo("WORK000000000001");
        assertThat(c.tyreSize()).isEqualTo("225/75 R 16");
        assertThat(c.kConstant()).isEqualTo(15150);
        assertThat(c.nextCalibrationDate()).isEqualTo(t("2025-04-21T18:00:00Z"));
    }

    @Test
    void gen1Summary() {
        TachoFileSummary s = TachoParser.parse(VuFixtures.gen1Vu()).summary();

        assertThat(s.kind()).isEqualTo(FileKind.VEHICLE_UNIT);
        assertThat(s.vin()).isEqualTo(VIN);
        assertThat(s.plate()).isEqualTo("TEST123");
        assertThat(s.plateCountry()).isEqualTo("PL");
        assertThat(s.periodFrom()).isEqualTo(LocalDate.of(2024, 3, 26));
        assertThat(s.periodTo()).isEqualTo(LocalDate.of(2024, 3, 27));
        assertThat(s.downloadedAt()).isEqualTo(t("2024-04-01T09:13:11Z"));
        assertThat(s.cardNumber()).isNull();
    }

    @Test
    void gen2Version1() {
        VuFile vu = TachoParser.parseVehicleUnit(VuFixtures.gen2Vu(false));

        assertThat(vu.generation()).isEqualTo(Generation.GEN2);
        assertThat(vu.warnings()).singleElement().asString().contains("7Eh");

        var o = vu.overview();
        assertThat(o.vin()).isEqualTo(VIN);
        assertThat(o.registration().number()).isEqualTo("TEST123");
        assertThat(o.registration().nation().isEmpty()).isTrue();
        assertThat(o.driverSlotCardType()).isEqualTo(1);
        assertThat(o.coDriverSlotCardType()).isEqualTo(1);
        assertThat(o.previousDownload().card().generation()).isEqualTo(2);
        assertThat(o.companyLocks()).isEmpty();
        assertThat(o.controls()).singleElement().satisfies(c -> assertThat(c.controlType()).isEqualTo(0x40));

        VuActivityDay day = vu.activityDays().getFirst();
        assertThat(day.generation()).isEqualTo(Generation.GEN2);
        assertThat(day.date()).isEqualTo(LocalDate.of(2024, 3, 26));
        assertThat(day.cardInsertions()).singleElement().satisfies(iw -> {
            assertThat(iw.card().number()).isEqualTo(CARD_NUMBER);
            assertThat(iw.previousVehicleWithdrawalTime()).isEqualTo(t("2024-03-25T16:00:00Z"));
            assertThat(iw.manualInput()).isFalse();
        });
        assertThat(day.changes()).hasSize(5);
        assertThat(day.places()).singleElement().satisfies(p -> {
            assertThat(p.place().gnss().latitude()).isEqualTo(52.223333);
            assertThat(p.place().gnss().authenticationStatus()).isNull();
        });
        assertThat(day.gnssAccumulatedDriving()).singleElement().satisfies(g -> {
            assertThat(g.driverCard().number()).isEqualTo(CARD_NUMBER);
            assertThat(g.coDriverCard()).isNull();
            assertThat(g.place().latitude()).isEqualTo(52.0);
            assertThat(g.odometerKm()).isEqualTo(1250);
        });

        var e = vu.eventsAndFaults();
        assertThat(e.faults()).singleElement().satisfies(f -> assertThat(f.typeKey()).isEqualTo("SENSOR_FAULT"));
        // the event and the power supply interruption of the technical data
        assertThat(e.events()).hasSize(2).allSatisfy(ev -> assertThat(ev.typeKey())
                .isEqualTo("POWER_SUPPLY_INTERRUPTION"));
        assertThat(e.events().getFirst().similarEvents()).isEqualTo(1);

        assertThat(vu.detailedSpeed()).singleElement().satisfies(b -> assertThat(b.speeds()[0]).isEqualTo(60));

        var td = vu.technicalData();
        assertThat(td.identification().approvalNumber()).isEqualTo("e1-200");
        assertThat(td.identification().vuGeneration()).isEqualTo(2);
        assertThat(td.identification().digitalMapVersion()).isNull();
        assertThat(td.pairedSensors()).singleElement()
                .satisfies(s -> assertThat(s.approvalNumber()).isEqualTo("e1-201"));
        assertThat(td.calibrations()).singleElement()
                .satisfies(c -> assertThat(c.registration().number()).isEqualTo("TEST123"));
    }

    @Test
    void gen2Version2() {
        VuFile vu = TachoParser.parseVehicleUnit(VuFixtures.gen2Vu(true));

        assertThat(vu.generation()).isEqualTo(Generation.GEN2V2);
        assertThat(vu.warnings()).isEmpty();
        assertThat(vu.overview().registration().nation().iso2()).isEqualTo("PL");

        VuActivityDay day = vu.activityDays().getFirst();
        assertThat(day.places().getFirst().place().gnss().authenticationStatus()).isEqualTo(1);
        assertThat(day.gnssAccumulatedDriving().getFirst().place().authenticationStatus()).isEqualTo(1);
        assertThat(day.borderCrossings()).singleElement().satisfies(b -> {
            assertThat(b.countryLeft().iso2()).isEqualTo("PL");
            assertThat(b.countryEntered().iso2()).isEqualTo("DE");
            assertThat(b.odometerKm()).isEqualTo(1280);
        });
        assertThat(day.loadUnloadOperations()).singleElement()
                .satisfies(l -> assertThat(l.operationType()).isEqualTo(2));
        assertThat(vu.technicalData().identification().digitalMapVersion()).isEqualTo("MAP-2023-01");

        TachoFileSummary s = vu.summary();
        assertThat(s.generation()).isEqualTo(Generation.GEN2V2);
        assertThat(s.plateCountry()).isEqualTo("PL");
    }

    @Test
    void truncatedFirstTransferFails() {
        byte[] data = VuFixtures.gen1OverviewOnly();
        byte[] cut = Arrays.copyOf(data, data.length - 10);

        assertThatThrownBy(() -> TachoParser.parse(cut))
                .isInstanceOfSatisfying(TachoFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TRUNCATED));
    }

    @Test
    void truncatedLaterTransferKeepsWhatWasRead() {
        byte[] data = VuFixtures.gen1Vu();
        byte[] cut = Arrays.copyOf(data, data.length - 10);

        VuFile vu = TachoParser.parseVehicleUnit(cut);
        assertThat(vu.overview().vin()).isEqualTo(VIN);
        assertThat(vu.activityDays()).hasSize(2);
        assertThat(vu.technicalData()).isNull();
        assertThat(vu.warnings()).singleElement().asString().contains("Transfer 05h");
    }
}
