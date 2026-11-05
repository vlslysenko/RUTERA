package com.rutera.core.push;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The choice of the lead time an item is in, and the setting that lists them. */
class DeadlineNoticesTest {

    private static final List<Integer> DAYS = List.of(1, 7, 14, 30);

    @Test
    void theSmallestLeadTimeNotBelowTheDaysLeft() {
        assertThat(DeadlineNotices.threshold(DAYS, 31, null)).isNull();
        assertThat(DeadlineNotices.threshold(DAYS, 30, null)).isEqualTo(30);
        assertThat(DeadlineNotices.threshold(DAYS, 15, null)).isEqualTo(30);
        assertThat(DeadlineNotices.threshold(DAYS, 14, null)).isEqualTo(14);
        assertThat(DeadlineNotices.threshold(DAYS, 8, null)).isEqualTo(14);
        assertThat(DeadlineNotices.threshold(DAYS, 7, null)).isEqualTo(7);
        assertThat(DeadlineNotices.threshold(DAYS, 2, null)).isEqualTo(7);
        assertThat(DeadlineNotices.threshold(DAYS, 1, null)).isEqualTo(1);
        assertThat(DeadlineNotices.threshold(DAYS, 0, null)).as("the last day").isEqualTo(1);
        assertThat(DeadlineNotices.threshold(DAYS, -1, null)).as("expired").isNull();
    }

    @Test
    void remindDaysDropsLongerLeadTimesAndIsOneItself() {
        assertThat(DeadlineNotices.threshold(DAYS, 20, 10)).isNull();
        assertThat(DeadlineNotices.threshold(DAYS, 10, 10)).isEqualTo(10);
        assertThat(DeadlineNotices.threshold(DAYS, 8, 10)).isEqualTo(10);
        assertThat(DeadlineNotices.threshold(DAYS, 7, 10)).isEqualTo(7);
        assertThat(DeadlineNotices.threshold(DAYS, 61, 60)).isNull();
        assertThat(DeadlineNotices.threshold(DAYS, 45, 60)).isEqualTo(60);
        assertThat(DeadlineNotices.threshold(DAYS, 30, 60)).isEqualTo(30);
        assertThat(DeadlineNotices.threshold(DAYS, 3, 0)).as("reminders off").isNull();
    }

    @Test
    void theSettingIsParsedAndAnUnusableOneGivesTheDefault() {
        assertThat(DeadlineNotices.parseDays(" 30, 7 ,1,7,-2,0")).containsExactly(1, 7, 30);
        assertThat(DeadlineNotices.parseDays("")).containsExactly(1, 7, 14, 30);
        assertThat(DeadlineNotices.parseDays("soon")).containsExactly(1, 7, 14, 30);
        assertThat(DeadlineNotices.parseDays(null)).containsExactly(1, 7, 14, 30);
    }
}
