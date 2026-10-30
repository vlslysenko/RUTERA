package com.rutera.tacho.io;

import com.rutera.tacho.TachoFormatException;
import com.rutera.tacho.TachoFormatException.Reason;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * Big-endian reader over a part of a byte array with bounds checks. All tachograph integers are unsigned and
 * big-endian; a read past the end throws {@link TachoFormatException} with reason TRUNCATED and the absolute offset.
 */
public final class ByteReader {

    private final byte[] data;
    private final int start;
    private final int end;
    private int pos;

    public ByteReader(byte[] data) {
        this(data, 0, data.length);
    }

    private ByteReader(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
        this.pos = start;
    }

    /** Position relative to the start of this reader. */
    public int position() {
        return pos - start;
    }

    /** Position in the whole file, for error messages. */
    public int absolutePosition() {
        return pos;
    }

    public int length() {
        return end - start;
    }

    public int remaining() {
        return end - pos;
    }

    public boolean hasRemaining() {
        return pos < end;
    }

    public void require(int n) {
        if (n < 0 || end - pos < n) {
            throw new TachoFormatException(Reason.TRUNCATED,
                    "Need " + n + " bytes, " + (end - pos) + " left", pos);
        }
    }

    public int u8() {
        require(1);
        return data[pos++] & 0xFF;
    }

    public int peekU8(int ahead) {
        if (pos + ahead >= end) {
            return -1;
        }
        return data[pos + ahead] & 0xFF;
    }

    public int u16() {
        require(2);
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    public int u24() {
        require(3);
        int v = ((data[pos] & 0xFF) << 16) | ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
        pos += 3;
        return v;
    }

    /** 24-bit two's complement (GeoCoordinates). */
    public int s24() {
        int v = u24();
        return (v & 0x800000) != 0 ? v - 0x1000000 : v;
    }

    public long u32() {
        require(4);
        long v = ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    public byte[] bytes(int n) {
        require(n);
        byte[] b = Arrays.copyOfRange(data, pos, pos + n);
        pos += n;
        return b;
    }

    public void skip(int n) {
        require(n);
        pos += n;
    }

    /** A reader over the next {@code n} bytes; this reader moves past them. */
    public ByteReader slice(int n) {
        require(n);
        ByteReader r = new ByteReader(data, pos, pos + n);
        pos += n;
        return r;
    }

    /** A new reader over the same bytes as this one, at its start. */
    public ByteReader duplicate() {
        return new ByteReader(data, start, end);
    }

    /** TimeReal: seconds since 1970-01-01 00:00 UTC; 0 and FFFFFFFFh (not set) give null. */
    public Instant timeReal() {
        long v = u32();
        return v == 0 || v == 0xFFFFFFFFL ? null : Instant.ofEpochSecond(v);
    }

    /** Datef: BCD yyyymmdd; zero or invalid gives null. */
    public LocalDate datef() {
        int year = bcd(2);
        int month = bcd(1);
        int day = bcd(1);
        if (year <= 0 || month <= 0 || day <= 0) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** {@code n} BCD bytes as a number; -1 when a nibble is not a digit. */
    public int bcd(int n) {
        require(n);
        int v = 0;
        boolean valid = true;
        for (int i = 0; i < n; i++) {
            int b = data[pos++] & 0xFF;
            int hi = b >> 4;
            int lo = b & 0x0F;
            if (hi > 9 || lo > 9) {
                valid = false;
            }
            v = v * 100 + hi * 10 + lo;
        }
        return valid ? v : -1;
    }

    /** IA5String of fixed length. */
    public String ia5(int n) {
        require(n);
        String s = TachoText.ia5(data, pos, n);
        pos += n;
        return s;
    }

    /** Code page byte followed by {@code n - 1} bytes of text (Name and Address: n = 36). */
    public String text(int n) {
        require(n);
        int codePage = data[pos] & 0xFF;
        String s = TachoText.decode(codePage, data, pos + 1, n - 1);
        pos += n;
        return s;
    }

    /** Name or Address: code page + 35 bytes. */
    public String name() {
        return text(36);
    }
}
