package com.example.market.domain.marketdata;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OhlcPeriodTest {

    @Test
    void bucketStart_oneMinute_truncatesToMinute() {
        var t = Instant.parse("2026-05-04T14:23:45.678Z");
        assertThat(OhlcPeriod.ONE_MIN.bucketStart(t))
                .isEqualTo(Instant.parse("2026-05-04T14:23:00Z"));
    }

    @Test
    void bucketStart_fiveMinute_alignsTo5MinuteBoundary() {
        // 14:23:xx 는 14:20 bucket
        assertThat(OhlcPeriod.FIVE_MIN.bucketStart(Instant.parse("2026-05-04T14:23:45Z")))
                .isEqualTo(Instant.parse("2026-05-04T14:20:00Z"));
        // 14:25:xx 는 14:25 bucket
        assertThat(OhlcPeriod.FIVE_MIN.bucketStart(Instant.parse("2026-05-04T14:25:00Z")))
                .isEqualTo(Instant.parse("2026-05-04T14:25:00Z"));
        // 14:00:xx 는 14:00 bucket
        assertThat(OhlcPeriod.FIVE_MIN.bucketStart(Instant.parse("2026-05-04T14:00:30Z")))
                .isEqualTo(Instant.parse("2026-05-04T14:00:00Z"));
    }

    @Test
    void bucketStart_oneHour_truncatesToHour() {
        var t = Instant.parse("2026-05-04T14:23:45Z");
        assertThat(OhlcPeriod.ONE_HOUR.bucketStart(t))
                .isEqualTo(Instant.parse("2026-05-04T14:00:00Z"));
    }

    @Test
    void bucketStart_oneDay_truncatesToUtcMidnight() {
        var t = Instant.parse("2026-05-04T14:23:45Z");
        assertThat(OhlcPeriod.ONE_DAY.bucketStart(t))
                .isEqualTo(Instant.parse("2026-05-04T00:00:00Z"));
    }

    @Test
    void bucketEnd_isStartPlusDuration() {
        var t = Instant.parse("2026-05-04T14:23:45Z");
        assertThat(OhlcPeriod.ONE_HOUR.bucketEnd(t))
                .isEqualTo(Instant.parse("2026-05-04T15:00:00Z"));
    }
}
