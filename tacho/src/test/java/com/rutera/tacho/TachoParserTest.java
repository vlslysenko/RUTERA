package com.rutera.tacho;

import com.rutera.tacho.TachoFormatException.Reason;
import com.rutera.tacho.card.DriverCardFile;
import com.rutera.tacho.vu.VuFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TachoParserTest {

    @Test
    void detectsTheKindOfFile() {
        assertThat(TachoParser.detect(CardFixtures.gen1Card())).isEqualTo(FileKind.DRIVER_CARD);
        assertThat(TachoParser.detect(CardFixtures.gen2Card(true))).isEqualTo(FileKind.DRIVER_CARD);
        assertThat(TachoParser.detect(VuFixtures.gen1Vu())).isEqualTo(FileKind.VEHICLE_UNIT);
        assertThat(TachoParser.detect(VuFixtures.gen2Vu(false))).isEqualTo(FileKind.VEHICLE_UNIT);

        assertThat(TachoParser.parse(CardFixtures.gen2Card(false))).isInstanceOf(DriverCardFile.class);
        assertThat(TachoParser.parse(VuFixtures.gen2Vu(true))).isInstanceOf(VuFile.class);
    }

    @Test
    void rejectsOtherFiles() {
        for (byte[] data : new byte[][] {
                new byte[0],
                "%PDF-1.7 not a tachograph file".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0x76, 0x07, 0, 0},
                new byte[] {0, 7, 9, 0, 1, 1}}) {
            assertThatThrownBy(() -> TachoParser.parse(data))
                    .isInstanceOfSatisfying(TachoFormatException.class,
                            e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_A_TACHOGRAPH_FILE));
        }
    }

    @Test
    void truncatedCardIsReportedAsTruncated() {
        byte[] card = CardFixtures.gen1Card();
        byte[] cut = Arrays.copyOf(card, card.length / 2);

        assertThatThrownBy(() -> TachoParser.parse(cut))
                .isInstanceOfSatisfying(TachoFormatException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TRUNCATED));
    }
}
