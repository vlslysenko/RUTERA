package com.rutera.tacho;

import com.rutera.tacho.model.Activity;
import com.rutera.tacho.model.ActivityChange;
import com.rutera.tacho.model.Slot;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;

/** Builds synthetic tachograph structures for tests (big-endian, padded like real data). */
final class DddWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    static Instant t(String iso) {
        return Instant.parse(iso);
    }

    byte[] toBytes() {
        return out.toByteArray();
    }

    int size() {
        return out.size();
    }

    DddWriter u8(int v) {
        out.write(v & 0xFF);
        return this;
    }

    DddWriter u16(int v) {
        return u8(v >> 8).u8(v);
    }

    DddWriter u24(int v) {
        return u8(v >> 16).u8(v >> 8).u8(v);
    }

    DddWriter u32(long v) {
        return u8((int) (v >> 24)).u8((int) (v >> 16)).u8((int) (v >> 8)).u8((int) v);
    }

    DddWriter bytes(byte[] b) {
        out.writeBytes(b);
        return this;
    }

    DddWriter fill(int n, int value) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) value);
        return bytes(b);
    }

    DddWriter zeros(int n) {
        return fill(n, 0);
    }

    DddWriter time(Instant t) {
        return u32(t == null ? 0 : t.getEpochSecond());
    }

    DddWriter time(String iso) {
        return time(t(iso));
    }

    DddWriter bcd(int value, int bytes) {
        String digits = String.format("%0" + (bytes * 2) + "d", value);
        for (int i = 0; i < bytes; i++) {
            u8(Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16));
        }
        return this;
    }

    DddWriter datef(LocalDate d) {
        return d == null ? zeros(4) : bcd(d.getYear(), 2).bcd(d.getMonthValue(), 1).bcd(d.getDayOfMonth(), 1);
    }

    DddWriter ia5(String s, int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) ' ');
        byte[] src = s == null ? new byte[0] : s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, b, 0, Math.min(n, src.length));
        return bytes(b);
    }

    /** Code page byte + text padded with spaces to {@code n - 1} bytes. */
    DddWriter text(int codePage, Charset charset, String s, int n) {
        u8(codePage);
        byte[] b = new byte[n - 1];
        Arrays.fill(b, (byte) ' ');
        byte[] src = s.getBytes(charset);
        System.arraycopy(src, 0, b, 0, Math.min(n - 1, src.length));
        return bytes(b);
    }

    /** Name or Address in ISO 8859-1 (code page 1). */
    DddWriter name(String s) {
        return text(1, StandardCharsets.ISO_8859_1, s, 36);
    }

    DddWriter card(int type, int nation, String number) {
        return u8(type).u8(nation).ia5(number, 16);
    }

    DddWriter cardGen(int type, int nation, String number, int generation) {
        return card(type, nation, number).u8(generation);
    }

    DddWriter noCard() {
        return zeros(18);
    }

    DddWriter noCardGen() {
        return zeros(19);
    }

    DddWriter vehicle(int nation, String plate) {
        return u8(nation).text(1, StandardCharsets.ISO_8859_1, plate, 14);
    }

    DddWriter activity(Slot slot, boolean crew, boolean cardPresent, Activity activity, int minute) {
        return u16(new ActivityChange(slot, crew, cardPresent, activity, minute).encode());
    }

    DddWriter activity(Activity activity, int minute) {
        return activity(Slot.DRIVER, false, true, activity, minute);
    }

    /** GNSSPlaceRecord with raw GeoCoordinates (±DDMM.M x 10). */
    DddWriter gnss(Instant t, int accuracy, int lat, int lon) {
        return time(t).u8(accuracy).u24(lat & 0xFFFFFF).u24(lon & 0xFFFFFF);
    }

    DddWriter gnssAuth(Instant t, int accuracy, int lat, int lon, int auth) {
        return gnss(t, accuracy, lat, lon).u8(auth);
    }

    /** Card download TLV: FID, appendix, length, value. */
    DddWriter tlv(int fid, int appendix, byte[] value) {
        return u16(fid).u8(appendix).u16(value.length).bytes(value);
    }

    /** Second generation VU record array. */
    DddWriter array(int type, int size, int count, byte[] records) {
        if (records.length != size * count) {
            throw new IllegalArgumentException(String.format("array %02X: %d bytes, expected %d x %d", type,
                    records.length, count, size));
        }
        return u8(type).u16(size).u16(count).bytes(records);
    }
}
