package com.rutera.tacho;

import com.rutera.tacho.model.Activity;
import com.rutera.tacho.model.Slot;

import java.nio.charset.StandardCharsets;

import static com.rutera.tacho.CardFixtures.CARD_NUMBER;
import static com.rutera.tacho.CardFixtures.D;
import static com.rutera.tacho.CardFixtures.PL;
import static com.rutera.tacho.DddWriter.t;

/** Synthetic vehicle unit downloads of all generations (no real vehicle or person). */
final class VuFixtures {

    static final String VIN = "TESTVIN0000000001";

    private VuFixtures() {
    }

    // ---------------------------------------------------------------- GEN1

    static byte[] gen1Vu() {
        DddWriter w = new DddWriter();
        overviewGen1(w);
        activitiesGen1(w, "2024-03-27T23:59:59Z", false);
        activitiesGen1(w, "2024-03-26T23:59:59Z", true);
        eventsGen1(w);
        speedGen1(w);
        technicalGen1(w);
        return w.toBytes();
    }

    static byte[] gen1OverviewOnly() {
        DddWriter w = new DddWriter();
        overviewGen1(w);
        return w.toBytes();
    }

    private static void overviewGen1(DddWriter w) {
        w.u8(0x76).u8(0x01).zeros(194).zeros(194).ia5(VIN, 17).vehicle(PL, "TEST123")
                .time("2024-04-01T09:13:11Z").time("2024-03-01T00:00:00Z").time("2024-04-01T00:00:00Z")
                .u8(0x01)
                .time("2024-03-22T13:35:14Z").card(4, PL, "COMP000000000001").name("TEST FIRMA");
        w.u8(1).time("2023-01-01T00:00:00Z").time(t("1970-01-01T00:00:00Z")).name("TEST FIRMA")
                .name("TEST STREET 1").card(4, PL, "COMP000000000001");
        w.u8(1).u8(0xC0).time("2024-03-10T09:00:00Z").card(3, D, "CTRL000000000001")
                .time("2024-02-10T00:00:00Z").time("2024-03-10T00:00:00Z");
        w.zeros(128);
    }

    private static void activitiesGen1(DddWriter w, String day, boolean full) {
        w.u8(0x76).u8(0x02).time(day).u24(1200);
        if (!full) {
            w.u16(0).u16(2).activity(Slot.DRIVER, false, false, Activity.BREAK_REST, 0)
                    .activity(Slot.CO_DRIVER, false, false, Activity.BREAK_REST, 0).u8(0).u16(0).zeros(128);
            return;
        }
        w.u16(1).name("TESTOWY").name("JAN").card(1, PL, CARD_NUMBER).time("2028-06-16T23:59:59Z")
                .time("2024-03-26T05:55:00Z").u24(1200).u8(0).time("2024-03-26T15:05:00Z").u24(1300)
                .vehicle(PL, "PREV1").time("2024-03-25T16:00:00Z").u8(0);
        w.u16(5).activity(Slot.DRIVER, false, false, Activity.BREAK_REST, 0)
                .activity(Slot.CO_DRIVER, false, false, Activity.BREAK_REST, 0)
                .activity(Activity.WORK, 355).activity(Activity.DRIVING, 380).activity(Activity.BREAK_REST, 900);
        w.u8(1).card(1, PL, CARD_NUMBER).time("2024-03-26T06:00:00Z").u8(0).u8(PL).u8(0).u24(1200);
        w.u16(0);
        w.zeros(128);
    }

    private static void eventsGen1(DddWriter w) {
        w.u8(0x76).u8(0x03);
        w.u8(1).u8(0x35).u8(0).time("2024-03-02T08:00:00Z").time("2024-03-02T08:30:00Z")
                .noCard().noCard().noCard().noCard();
        w.u8(1).u8(0x08).u8(0).time("2024-03-01T10:00:00Z").time("2024-03-01T10:05:00Z")
                .card(1, PL, CARD_NUMBER).noCard().card(1, PL, CARD_NUMBER).noCard().u8(2);
        w.time("2024-01-19T07:26:43Z").u32(0).u8(0);
        w.u8(1).u8(0x07).u8(1).time("2024-03-26T10:00:00Z").time("2024-03-26T10:02:00Z").u8(98).u8(91)
                .card(1, PL, CARD_NUMBER).u8(1);
        w.u8(0);
        w.zeros(128);
    }

    private static void speedGen1(DddWriter w) {
        w.u8(0x76).u8(0x04).u16(1).time("2024-03-26T10:00:00Z");
        for (int i = 0; i < 60; i++) {
            w.u8(i);
        }
        w.zeros(128);
    }

    private static void technicalGen1(DddWriter w) {
        w.u8(0x76).u8(0x05).name("TEST VU MAKER").name("TEST STREET 2").ia5("PART-0001", 16)
                .u32(408128).bcd(219, 2).u8(6).u8(0xA1).ia5("3037", 4).time("2019-02-16T00:00:00Z")
                .time("2019-02-16T00:00:00Z").ia5("e1-84", 8);
        w.u32(77).bcd(219, 2).u8(7).u8(0xA1).ia5("e1-85", 8).time("2019-04-19T00:00:00Z");
        w.u8(1);
        calibration(w);
        w.zeros(128);
    }

    private static void calibration(DddWriter w) {
        w.u8(4).name("TEST WORKSHOP").name("TEST STREET 3").card(2, PL, "WORK000000000001")
                .time("2025-01-01T00:00:00Z").ia5(VIN, 17).vehicle(PL, "TEST123").u16(15150).u16(15150)
                .u16(18344).ia5("225/75 R 16", 15).u8(90).u24(1000).u24(1000).time("2023-04-21T07:08:00Z")
                .time("2023-04-21T07:08:03Z").time("2025-04-21T18:00:00Z");
    }

    // ---------------------------------------------------------------- second generation

    /** Second generation VU, version 1 (TREP 21h..25h) or version 2 (31h..35h). */
    static byte[] gen2Vu(boolean version2) {
        int base = version2 ? 0x30 : 0x20;
        DddWriter w = new DddWriter();
        overviewGen2(w, base, version2);
        activitiesGen2(w, base, version2);
        eventsGen2(w, base);
        w.u8(0x76).u8(base + 4);
        DddWriter speed = new DddWriter().time("2024-03-26T10:00:00Z");
        for (int i = 0; i < 60; i++) {
            speed.u8(60 - i);
        }
        w.array(0x12, 64, 1, speed.toBytes()).array(0x08, 64, 1, new byte[64]);
        technicalGen2(w, base, version2);
        return w.toBytes();
    }

    private static void overviewGen2(DddWriter w, int base, boolean version2) {
        w.u8(0x76).u8(base + 1);
        w.array(0x04, 205, 1, new byte[205]).array(0x0F, 205, 1, new byte[205]);
        w.array(0x0A, 17, 1, new DddWriter().ia5(VIN, 17).toBytes());
        if (version2) {
            w.array(0x24, 15, 1, new DddWriter().vehicle(PL, "TEST123").toBytes());
        } else {
            w.array(0x0B, 14, 1, new DddWriter().text(1, StandardCharsets.ISO_8859_1, "TEST123", 14).toBytes());
            w.array(0x7E, 2, 1, new byte[2]); // unknown array: skipped with a warning
        }
        w.array(0x03, 4, 1, new DddWriter().time("2024-04-01T09:13:11Z").toBytes());
        w.array(0x13, 8, 1, new DddWriter().time("2024-03-01T00:00:00Z").time("2024-04-01T00:00:00Z").toBytes());
        w.array(0x02, 1, 1, new byte[] {0x11});
        w.array(0x14, 59, 1, new DddWriter().time("2024-03-22T13:35:14Z").cardGen(4, PL, "COMP000000000001", 2)
                .name("TEST FIRMA").toBytes());
        w.array(0x10, 99, 0, new byte[0]);
        w.array(0x11, 32, 1, new DddWriter().u8(0x40).time("2024-03-10T09:00:00Z")
                .cardGen(3, D, "CTRL000000000001", 2).time("2024-02-10T00:00:00Z").time("2024-03-10T00:00:00Z")
                .toBytes());
        w.array(0x08, 64, 1, new byte[64]);
    }

    private static void activitiesGen2(DddWriter w, int base, boolean version2) {
        w.u8(0x76).u8(base + 2);
        w.array(0x06, 4, 1, new DddWriter().time("2024-03-26T00:00:00Z").toBytes());
        w.array(0x05, 3, 1, new DddWriter().u24(1200).toBytes());
        w.array(0x0D, 131, 1, new DddWriter().name("TESTOWY").name("JAN").cardGen(1, PL, CARD_NUMBER, 2)
                .time("2028-06-16T23:59:59Z").time("2024-03-26T05:55:00Z").u24(1200).u8(0)
                .time("2024-03-26T15:05:00Z").u24(1300).vehicle(PL, "PREV1").time("2024-03-25T16:00:00Z").u8(2)
                .u8(0).toBytes());
        DddWriter changes = new DddWriter().activity(Slot.DRIVER, false, false, Activity.BREAK_REST, 0)
                .activity(Slot.CO_DRIVER, false, false, Activity.BREAK_REST, 0)
                .activity(Activity.WORK, 355).activity(Activity.DRIVING, 380).activity(Activity.BREAK_REST, 900);
        w.array(0x01, 2, 5, changes.toBytes());
        DddWriter place = new DddWriter().cardGen(1, PL, CARD_NUMBER, 2).time("2024-03-26T06:00:00Z").u8(0).u8(PL)
                .u8(0).u24(1200);
        if (version2) {
            place.gnssAuth(t("2024-03-26T06:00:00Z"), 4, 52134, 21005, 1);
        } else {
            place.gnss(t("2024-03-26T06:00:00Z"), 4, 52134, 21005);
        }
        w.array(0x1C, version2 ? 41 : 40, 1, place.toBytes());
        DddWriter gnss = new DddWriter().time("2024-03-26T09:20:00Z").cardGen(1, PL, CARD_NUMBER, 2).noCardGen();
        if (version2) {
            gnss.gnssAuth(t("2024-03-26T09:20:00Z"), 4, 52000, 20000, 1);
        } else {
            gnss.gnss(t("2024-03-26T09:20:00Z"), 4, 52000, 20000);
        }
        gnss.u24(1250);
        w.array(0x16, version2 ? 57 : 56, 1, gnss.toBytes());
        w.array(0x09, 5, 0, new byte[0]);
        if (version2) {
            w.array(0x22, 55, 1, new DddWriter().cardGen(1, PL, CARD_NUMBER, 2).noCardGen().u8(PL).u8(D)
                    .gnssAuth(t("2024-03-26T11:00:00Z"), 3, 52200, 14300, 1).u24(1280).toBytes());
            w.array(0x23, 58, 1, new DddWriter().time("2024-03-26T08:00:00Z").u8(2).cardGen(1, PL, CARD_NUMBER, 2)
                    .noCardGen().gnssAuth(t("2024-03-26T08:00:00Z"), 3, 52100, 21000, 1).u24(1240).toBytes());
        }
        w.array(0x08, 64, 1, new byte[64]);
    }

    private static void eventsGen2(DddWriter w, int base) {
        w.u8(0x76).u8(base + 3);
        w.array(0x18, 101, 1, new DddWriter().u8(0x35).u8(0).time("2024-03-02T08:00:00Z")
                .time("2024-03-02T08:30:00Z").noCardGen().noCardGen().noCardGen().noCardGen().zeros(15).toBytes());
        w.array(0x15, 102, 1, new DddWriter().u8(0x08).u8(0).time("2024-03-01T10:00:00Z")
                .time("2024-03-01T10:05:00Z").cardGen(1, PL, CARD_NUMBER, 2).noCardGen().noCardGen().noCardGen()
                .u8(1).zeros(15).toBytes());
        w.array(0x1A, 9, 1, new DddWriter().time("2024-01-19T07:26:43Z").u32(0).u8(0).toBytes());
        w.array(0x1B, 32, 0, new byte[0]);
        w.array(0x1E, 99, 0, new byte[0]);
        w.array(0x08, 64, 1, new byte[64]);
    }

    private static void technicalGen2(DddWriter w, int base, boolean version2) {
        w.u8(0x76).u8(base + 5);
        DddWriter id = new DddWriter().name("TEST VU MAKER").name("TEST STREET 2").ia5("PART-0002", 16)
                .u32(500001).bcd(1023, 2).u8(6).u8(0xA1).ia5("0400", 4).time("2023-10-01T00:00:00Z")
                .time("2023-10-01T00:00:00Z").ia5("e1-200", 16).u8(2).u8(0);
        if (version2) {
            id.ia5("MAP-2023-01", 12);
        }
        w.array(0x19, version2 ? 138 : 126, 1, id.toBytes());
        w.array(0x20, 28, 1, new DddWriter().u32(88).bcd(1023, 2).u8(7).u8(0xA1).ia5("e1-201", 16)
                .time("2023-10-02T00:00:00Z").toBytes());
        w.array(0x21, 28, 0, new byte[0]);
        DddWriter calibration = new DddWriter();
        calibration(calibration);
        calibration.zeros(74);
        w.array(0x0C, 241, 1, calibration.toBytes());
        w.array(0x0E, 37, 0, new byte[0]);
        w.array(0x17, 20, 0, new byte[0]);
        w.array(0x1F, 87, 1, new DddWriter().u8(0x08).u8(7).time("2024-03-20T10:00:00Z").time(t("1970-01-01T00:00:00Z"))
                .noCardGen().noCardGen().noCardGen().noCardGen().u8(0).toBytes());
        w.array(0x08, 64, 1, new byte[64]);
    }
}
