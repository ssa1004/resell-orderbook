package com.example.market.domain.inspection.scheduling;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionCenterTest {

    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static InspectionCenter aCenter() {
        return InspectionCenter.open("Seoul Center", "Gangnam-gu",
                5, Duration.ofHours(1), Duration.ofMinutes(30), CLOCK);
    }

    @Test
    void open_rejectsZeroCapacity() {
        assertThatThrownBy(() -> InspectionCenter.open("X", "Y", 0,
                Duration.ofHours(1), Duration.ofMinutes(30), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelCapacity");
    }

    @Test
    void slotStartFor_oneHour_truncatesToHour() {
        InspectionCenter c = aCenter();
        Instant t = Instant.parse("2026-05-04T14:23:45Z");
        assertThat(c.slotStartFor(t)).isEqualTo(Instant.parse("2026-05-04T14:00:00Z"));
    }

    @Test
    void slotStartFor_30Min_alignsTo30MinuteBoundary() {
        InspectionCenter c = InspectionCenter.open("X", "Y", 3,
                Duration.ofMinutes(30), Duration.ofMinutes(30), CLOCK);
        // 14:23 → 14:00 / 14:45 → 14:30
        assertThat(c.slotStartFor(Instant.parse("2026-05-04T14:23:45Z")))
                .isEqualTo(Instant.parse("2026-05-04T14:00:00Z"));
        assertThat(c.slotStartFor(Instant.parse("2026-05-04T14:45:00Z")))
                .isEqualTo(Instant.parse("2026-05-04T14:30:00Z"));
    }

    @Test
    void isWithinLeadTime_tooSoon() {
        InspectionCenter c = aCenter();
        Instant slot = Instant.parse("2026-05-04T10:20:00Z");   // 20분 후 — lead time(30분) 안
        assertThat(c.isWithinLeadTime(slot, NOW)).isTrue();
    }

    @Test
    void isWithinLeadTime_safelyFar() {
        InspectionCenter c = aCenter();
        Instant slot = Instant.parse("2026-05-04T11:00:00Z");   // 1시간 후
        assertThat(c.isWithinLeadTime(slot, NOW)).isFalse();
    }
}
