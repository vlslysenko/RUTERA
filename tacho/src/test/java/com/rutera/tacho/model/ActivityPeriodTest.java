package com.rutera.tacho.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityPeriodTest {

    @Test
    void decodesActivityChangeInfoBits() {
        // s=1 c=1 p=0 aa=11 t=00001111000 (120)
        ActivityChange c = ActivityChange.decode(0b1101_1000_0111_1000);
        assertThat(c.slot()).isEqualTo(Slot.CO_DRIVER);
        assertThat(c.crew()).isTrue();
        assertThat(c.cardPresent()).isTrue();
        assertThat(c.activity()).isEqualTo(Activity.DRIVING);
        assertThat(c.minuteOfDay()).isEqualTo(120);
        assertThat(c.encode()).isEqualTo(0b1101_1000_0111_1000);

        ActivityChange manual = ActivityChange.decode(0b0110_0000_0000_0000 | (2 << 11) | 600);
        assertThat(manual.cardPresent()).isFalse();
        assertThat(manual.isManualEntry()).isTrue();
        assertThat(manual.activity()).isEqualTo(Activity.WORK);
    }

    @Test
    void periodsPerSlotUntilNextChangeOrMidnight() {
        LocalDate day = LocalDate.of(2024, 3, 26);
        List<ActivityChange> changes = List.of(
                change(Slot.DRIVER, Activity.BREAK_REST, 0),
                change(Slot.CO_DRIVER, Activity.AVAILABILITY, 0),
                change(Slot.DRIVER, Activity.DRIVING, 360),
                change(Slot.DRIVER, Activity.DRIVING, 360),   // duplicate minute: zero length, dropped
                change(Slot.DRIVER, Activity.BREAK_REST, 300), // goes back in time: ignored
                change(Slot.DRIVER, Activity.WORK, 630));

        List<ActivityPeriod> periods = ActivityPeriod.of(day, changes);

        assertThat(periods).hasSize(4);
        ActivityPeriod driving = periods.stream().filter(p -> p.activity() == Activity.DRIVING).findFirst()
                .orElseThrow();
        assertThat(driving.start()).isEqualTo(Instant.parse("2024-03-26T06:00:00Z"));
        assertThat(driving.end()).isEqualTo(Instant.parse("2024-03-26T10:30:00Z"));
        ActivityPeriod coDriver = periods.stream().filter(p -> p.slot() == Slot.CO_DRIVER).findFirst()
                .orElseThrow();
        assertThat(coDriver.minutes()).isEqualTo(1440);
        assertThat(periods.getLast().end()).isEqualTo(Instant.parse("2024-03-27T00:00:00Z"));
    }

    @Test
    void geoCoordinatesAreDegreesAndMinutesTimesTen() {
        assertThat(GnssPlace.toDegrees(52134, 90)).isEqualTo(52.223333);
        assertThat(GnssPlace.toDegrees(-4030, 90)).isEqualTo(-4.05);
        assertThat(GnssPlace.toDegrees(0x7FFFFF, 90)).isNull();
        assertThat(GnssPlace.toDegrees(90001, 90)).isNull();
        assertThat(GnssPlace.toDegrees(52634, 90)).isNull(); // 63.4 minutes
    }

    @Test
    void nations() {
        assertThat(Nation.of(0x31).iso2()).isEqualTo("UA");
        assertThat(Nation.of(0x0D).tachoCode()).isEqualTo("D");
        assertThat(Nation.of(0xFD).iso2()).isNull();
        assertThat(Nation.of(0xFD).display()).isEqualTo("EC");
        assertThat(Nation.of(0x60).display()).isEqualTo("60");
        assertThat(EventFaultTypes.key(0x07)).isEqualTo("OVER_SPEEDING");
        assertThat(EventFaultTypes.key(0x91)).isEqualTo("MANUFACTURER_91");
        assertThat(EventFaultTypes.isFault(0x35)).isTrue();
    }

    private static ActivityChange change(Slot slot, Activity activity, int minute) {
        return new ActivityChange(slot, false, true, activity, minute);
    }
}
