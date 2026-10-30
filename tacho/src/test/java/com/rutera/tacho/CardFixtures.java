package com.rutera.tacho;

import com.rutera.tacho.model.Activity;
import com.rutera.tacho.model.Slot;

import java.time.LocalDate;

import static com.rutera.tacho.DddWriter.t;

/**
 * Synthetic driver card downloads (no real person): a GEN1 card and second generation cards with both
 * applications. The GEN1 activity buffer wraps around its end to test the cyclic walk.
 */
final class CardFixtures {

    static final String CARD_NUMBER = "TST0000000000100";
    static final int PL = 0x28;
    static final int D = 0x0D;

    private CardFixtures() {
    }

    static byte[] gen1Card() {
        DddWriter w = new DddWriter();
        masterFile(w);
        gen1Application(w, 1, 100, activityGen1Wrapping());
        return w.toBytes();
    }

    static byte[] gen2Card(boolean version2) {
        DddWriter w = new DddWriter();
        masterFile(w);
        gen1Application(w, 1, 100, activityGen1Wrapping());
        gen2Application(w, version2);
        return w.toBytes();
    }

    /** A company card: same structure, card type 4 in the application identification. */
    static byte[] companyCard() {
        DddWriter w = new DddWriter();
        masterFile(w);
        gen1Application(w, 4, 100, activityGen1Wrapping());
        return w.toBytes();
    }

    /** GEN1 card whose activity EF has an impossible record length; every other EF is fine. */
    static byte[] cardWithBrokenActivity() {
        DddWriter activity = new DddWriter().u16(0).u16(0).u16(0).u16(5).zeros(96);
        DddWriter w = new DddWriter();
        masterFile(w);
        gen1Application(w, 1, 100, activity.toBytes());
        return w.toBytes();
    }

    private static void masterFile(DddWriter w) {
        DddWriter icc = new DddWriter().u8(0).u32(12345).bcd(623, 2).u8(1).u8(0xA3).ia5("e5-0360", 8).u8(0xA3)
                .zeros(5).zeros(2);
        w.tlv(0x0002, 0, icc.toBytes());
        w.tlv(0x0005, 0, new byte[8]);
    }

    private static void gen1Application(DddWriter w, int cardType, int activityLength, byte[] activity) {
        int sig = 128;
        signed(w, 0x0501, 0, sig, new DddWriter().u8(cardType).u16(0).u8(2).u8(2).u16(activityLength).u16(4).u8(5)
                .toBytes());
        w.tlv(0xC100, 0, new byte[194]);
        w.tlv(0xC108, 0, new byte[194]);
        signed(w, 0x0520, 0, sig, identification());
        signed(w, 0x050E, 0, sig, new DddWriter().time("2024-03-22T15:58:03Z").toBytes());
        signed(w, 0x0521, 0, sig, licence());

        DddWriter events = new DddWriter();
        powerSupplyEvent(events);
        events.u8(0x04).time("2024-02-01T07:00:00Z").time("2024-02-01T07:10:00Z").vehicle(PL, "TEST123");
        events.zeros(10 * 24);
        signed(w, 0x0502, 0, sig, events.toBytes());

        DddWriter faults = new DddWriter();
        faults.u8(0x35).time("2024-03-02T08:00:00Z").time("2024-03-02T08:30:00Z").vehicle(PL, "TEST123");
        faults.zeros(3 * 24);
        signed(w, 0x0503, 0, sig, faults.toBytes());

        signed(w, 0x0504, 0, sig, activity);

        DddWriter vehicles = new DddWriter().u16(1);
        vehicles.u24(1000).u24(1200).time("2024-03-25T06:00:00Z").time("2024-03-25T16:00:00Z")
                .vehicle(PL, "TEST123").bcd(1, 2);
        vehicles.u24(1200).u24(1300).time("2024-03-26T06:00:00Z").time("2024-03-26T15:00:00Z")
                .vehicle(PL, "TEST123").bcd(2, 2);
        vehicles.zeros(2 * 31);
        signed(w, 0x0505, 0, sig, vehicles.toBytes());

        DddWriter places = new DddWriter().u8(1);
        places.time("2024-03-25T06:00:00Z").u8(0).u8(PL).u8(0).u24(1000);
        places.time("2024-03-25T16:00:00Z").u8(1).u8(D).u8(0).u24(1200);
        places.zeros(3 * 10);
        signed(w, 0x0506, 0, sig, places.toBytes());

        signed(w, 0x0507, 0, sig, new DddWriter().time("2024-03-27T06:00:00Z").vehicle(PL, "TEST123").toBytes());
        signed(w, 0x0508, 0, sig, control());

        DddWriter conditions = new DddWriter();
        conditions.time("2024-03-26T12:00:00Z").u8(3);
        conditions.zeros(3 * 5);
        signed(w, 0x0522, 0, sig, conditions.toBytes());
    }

    private static void gen2Application(DddWriter w, boolean version2) {
        int sig = 64;
        signed(w, 0x0501, 2, sig, new DddWriter().u8(1).u16(0x0100).u8(2).u8(2).u16(60).u16(3).u16(3).u16(2)
                .u16(2).u16(2).toBytes());
        if (version2) {
            signed(w, 0x0525, 2, sig, new DddWriter().u16(7).u16(2).u16(2).u16(2).u8(0).toBytes());
        }
        w.tlv(0xC100, 2, new byte[204]);
        w.tlv(0xC101, 2, new byte[204]);
        w.tlv(0xC108, 2, new byte[204]);
        w.tlv(0xC109, 2, new byte[204]);
        signed(w, 0x0520, 2, sig, identification());
        signed(w, 0x050E, 2, sig, new DddWriter().time("2024-03-22T15:58:03Z").toBytes());
        signed(w, 0x0521, 2, sig, licence());

        DddWriter events = new DddWriter();
        powerSupplyEvent(events);
        events.u8(0x0A).time("2024-03-26T10:00:00Z").time("2024-03-26T10:01:00Z").vehicle(PL, "TEST123");
        events.zeros(20 * 24);
        signed(w, 0x0502, 2, sig, events.toBytes());
        signed(w, 0x0503, 2, sig, new byte[4 * 24]);

        // no wrap-around: day 2024-03-26 (more changes than in GEN1) and 2024-03-27
        DddWriter activity = new DddWriter().u16(0).u16(20);
        activity.u16(0).u16(20).time("2024-03-26T00:00:00Z").bcd(2, 2).u16(100)
                .activity(Activity.BREAK_REST, 0).activity(Activity.WORK, 360).activity(Activity.DRIVING, 380)
                .activity(Activity.BREAK_REST, 900);
        activity.u16(20).u16(14).time("2024-03-27T00:00:00Z").bcd(3, 2).u16(0)
                .activity(Activity.BREAK_REST, 0);
        activity.zeros(60 - 34);
        signed(w, 0x0504, 2, sig, activity.toBytes());

        DddWriter vehicles = new DddWriter().u16(0);
        vehicles.u24(1200).u24(1300).time("2024-03-26T06:00:00Z").time("2024-03-26T15:00:00Z")
                .vehicle(PL, "TEST123").bcd(2, 2).ia5("TESTVIN0000000001", 17);
        vehicles.zeros(2 * 48);
        signed(w, 0x0505, 2, sig, vehicles.toBytes());

        DddWriter places = new DddWriter().u16(1);
        places.time("2024-03-25T06:00:00Z").u8(0).u8(PL).u8(0).u24(1000)
                .gnss(t("2024-03-25T06:00:00Z"), 5, 52134, 21005);
        places.time("2024-03-26T06:00:00Z").u8(0).u8(PL).u8(0).u24(1200)
                .gnss(t("2024-03-26T06:00:00Z"), 5, -33456, -70402);
        places.zeros(21);
        signed(w, 0x0506, 2, sig, places.toBytes());

        signed(w, 0x0507, 2, sig, new DddWriter().time("2024-03-27T06:00:00Z").vehicle(PL, "TEST123").toBytes());
        signed(w, 0x0508, 2, sig, control());

        DddWriter conditions = new DddWriter().u16(0);
        conditions.time("2024-03-26T12:00:00Z").u8(3);
        conditions.time("2024-03-26T13:00:00Z").u8(4);
        signed(w, 0x0522, 2, sig, conditions.toBytes());

        DddWriter units = new DddWriter().u16(0);
        units.time("2024-03-26T06:00:00Z").u8(0xA1).u8(1).ia5("0300", 4);
        units.zeros(10);
        signed(w, 0x0523, 2, sig, units.toBytes());

        DddWriter gnss = new DddWriter().u16(0);
        gnss.time("2024-03-26T09:00:00Z").gnss(t("2024-03-26T09:00:00Z"), 5, 52134, 21005).u24(1250);
        gnss.zeros(18);
        signed(w, 0x0524, 2, sig, gnss.toBytes());

        if (version2) {
            DddWriter borders = new DddWriter().u16(0);
            borders.u8(PL).u8(D).gnssAuth(t("2024-03-26T11:00:00Z"), 3, 52200, 14300, 1).u24(1280);
            borders.zeros(17);
            signed(w, 0x0528, 2, sig, borders.toBytes());

            DddWriter loads = new DddWriter().u16(0);
            loads.time("2024-03-26T08:00:00Z").u8(1).gnssAuth(t("2024-03-26T08:00:00Z"), 3, 52100, 21000, 1)
                    .u24(1240);
            loads.zeros(20);
            signed(w, 0x0529, 2, sig, loads.toBytes());

            DddWriter loadTypes = new DddWriter().u16(0);
            loadTypes.time("2024-03-26T05:00:00Z").u8(1);
            loadTypes.zeros(5);
            signed(w, 0x0530, 2, sig, loadTypes.toBytes());
        }
    }

    /**
     * 100 byte buffer, oldest record at 80: day 25 (16 bytes, 80..95), day 26 (20 bytes, 96..99 and 0..15),
     * day 27 (18 bytes, 16..33, newest).
     */
    private static byte[] activityGen1Wrapping() {
        byte[] day25 = new DddWriter().u16(0).u16(16).time("2024-03-25T00:00:00Z").bcd(1, 2).u16(200)
                .activity(Activity.BREAK_REST, 0).activity(Activity.DRIVING, 360).toBytes();
        byte[] day26 = new DddWriter().u16(16).u16(20).time("2024-03-26T00:00:00Z").bcd(2, 2).u16(100)
                .activity(Activity.BREAK_REST, 0).activity(Activity.DRIVING, 360)
                .activity(Activity.BREAK_REST, 900)
                .activity(Slot.DRIVER, true, false, Activity.WORK, 1200).toBytes();
        byte[] day27 = new DddWriter().u16(20).u16(18).time("2024-03-27T00:00:00Z").bcd(3, 2).u16(0)
                .activity(Activity.BREAK_REST, 0).activity(Activity.WORK, 360).activity(Activity.BREAK_REST, 420)
                .toBytes();
        byte[] buffer = new byte[100];
        put(buffer, 80, day25);
        put(buffer, 96, day26);
        put(buffer, 16, day27);
        return new DddWriter().u16(80).u16(16).bytes(buffer).toBytes();
    }

    private static void put(byte[] buffer, int at, byte[] record) {
        for (int i = 0; i < record.length; i++) {
            buffer[(at + i) % buffer.length] = record[i];
        }
    }

    private static void powerSupplyEvent(DddWriter w) {
        w.u8(0x08).time("2024-03-01T10:00:00Z").time("2024-03-01T10:05:00Z").vehicle(PL, "TEST123");
    }

    private static byte[] identification() {
        return new DddWriter().u8(PL).ia5(CARD_NUMBER, 16).name("TEST AUTHORITY")
                .time("2023-06-17T00:00:00Z").time("2023-06-17T00:00:00Z").time("2028-06-16T23:59:59Z")
                .name("TESTOWY").name("JAN").datef(LocalDate.of(1980, 1, 2)).ia5("pl", 2).toBytes();
    }

    private static byte[] licence() {
        return new DddWriter().name("TEST LICENCE AUTHORITY").u8(PL).ia5("TESTLIC0001", 16).toBytes();
    }

    private static byte[] control() {
        return new DddWriter().u8(0xC0).time("2024-03-10T09:00:00Z").card(3, D, "CTRL000000000001")
                .vehicle(PL, "TEST123").time("2024-02-10T00:00:00Z").time("2024-03-10T00:00:00Z").toBytes();
    }

    private static void signed(DddWriter w, int fid, int appendix, int signatureSize, byte[] value) {
        w.tlv(fid, appendix, value);
        w.tlv(fid, appendix + 1, new byte[signatureSize]);
    }
}
