package com.rutera.tacho.io;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TachoTextTest {

    @Test
    void decodesCodePages() {
        assertThat(decode(2, "ISO-8859-2", "ŁÓDŹ ŻÓŁW")).isEqualTo("ŁÓDŹ ŻÓŁW");
        assertThat(decode(5, "ISO-8859-5", "ШЕВЧЕНКО")).isEqualTo("ШЕВЧЕНКО");
        assertThat(decode(85, "KOI8-U", "ҐАЛІЦЬКИЙ")).isEqualTo("ҐАЛІЦЬКИЙ");
        assertThat(decode(7, "ISO-8859-7", "ΠΑΠΑΔΟΠΟΥΛΟΣ")).isEqualTo("ΠΑΠΑΔΟΠΟΥΛΟΣ");
        assertThat(decode(1, "ISO-8859-1", "MÜLLER")).isEqualTo("MÜLLER");
    }

    @Test
    void trimsPaddingAndBlankIsNull() {
        byte[] b = new byte[10];
        Arrays.fill(b, (byte) 0xFF);
        assertThat(TachoText.decode(1, b, 0, b.length)).isNull();
        assertThat(TachoText.ia5(new byte[] {'A', 'B', ' ', 0, 0}, 0, 5)).isEqualTo("AB");
        assertThat(TachoText.ia5(new byte[] {' ', ' '}, 0, 2)).isNull();
    }

    @Test
    void readerReadsBcdDatesAndTime() {
        ByteReader r = new ByteReader(new byte[] {0x19, (byte) 0x80, 0x01, 0x02, 0, 0, 0, 0, 0x65, 0x5B, 0x4C, 0x00});
        assertThat(r.datef()).hasToString("1980-01-02");
        assertThat(r.timeReal()).isNull();
        assertThat(r.timeReal()).hasToString("2023-11-20T12:07:28Z");
        assertThat(r.hasRemaining()).isFalse();
    }

    private static String decode(int codePage, String charset, String text) {
        byte[] bytes = Arrays.copyOf(text.getBytes(Charset.forName(charset)), 35);
        return TachoText.decode(codePage, bytes, 0, bytes.length);
    }
}
