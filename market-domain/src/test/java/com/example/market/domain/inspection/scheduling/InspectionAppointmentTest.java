package com.example.market.domain.inspection.scheduling;

import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionAppointmentTest {

    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TradeId TRADE = TradeId.of(UUID.randomUUID().toString());
    private static final InspectionCenterId CENTER = InspectionCenterId.newId();

    private static InspectionAppointment freshReservation() {
        return InspectionAppointment.book(TRADE, CENTER,
                Instant.parse("2026-05-04T14:00:00Z"),
                Instant.parse("2026-05-04T15:00:00Z"),
                CLOCK);
    }

    @Test
    void book_initialState_RESERVED() {
        InspectionAppointment a = freshReservation();
        assertThat(a.status()).isEqualTo(AppointmentStatus.RESERVED);
        assertThat(a.bookedAt()).isEqualTo(NOW);
    }

    @Test
    void book_slotEndMustBeAfterStart() {
        Instant t = Instant.parse("2026-05-04T14:00:00Z");
        assertThatThrownBy(() -> InspectionAppointment.book(TRADE, CENTER, t, t, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markArrived_transitions() {
        InspectionAppointment a = freshReservation();
        a.markArrived(CLOCK);
        assertThat(a.status()).isEqualTo(AppointmentStatus.ARRIVED);
        assertThat(a.arrivedAt()).isEqualTo(NOW);
    }

    @Test
    void markCompleted_onlyFromArrived() {
        InspectionAppointment a = freshReservation();
        assertThatThrownBy(() -> a.markCompleted(CLOCK))
                .isInstanceOf(IllegalStateException.class);
        a.markArrived(CLOCK);
        a.markCompleted(CLOCK);
        assertThat(a.status()).isEqualTo(AppointmentStatus.COMPLETED);
    }

    @Test
    void cancel_onlyFromReserved() {
        InspectionAppointment a = freshReservation();
        a.cancel(CLOCK);
        assertThat(a.status()).isEqualTo(AppointmentStatus.CANCELLED);
        // 두 번 cancel 안 됨
        assertThatThrownBy(() -> a.cancel(CLOCK)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noShow_releaseCapacity() {
        InspectionAppointment a = freshReservation();
        a.markNoShow(CLOCK);
        assertThat(a.status()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(a.status().isOccupyingCapacity()).isFalse();
    }

    @Test
    void statusBoolean_isOccupyingCapacity() {
        assertThat(AppointmentStatus.RESERVED.isOccupyingCapacity()).isTrue();
        assertThat(AppointmentStatus.ARRIVED.isOccupyingCapacity()).isTrue();
        // 종착은 capacity 회수
        assertThat(AppointmentStatus.COMPLETED.isOccupyingCapacity()).isFalse();
        assertThat(AppointmentStatus.CANCELLED.isOccupyingCapacity()).isFalse();
        assertThat(AppointmentStatus.NO_SHOW.isOccupyingCapacity()).isFalse();
        assertThat(AppointmentStatus.REJECTED.isOccupyingCapacity()).isFalse();
    }

    @Test
    void slotDuration_oneHour() {
        InspectionAppointment a = freshReservation();
        assertThat(Duration.between(a.slotStart(), a.slotEnd())).isEqualTo(Duration.ofHours(1));
    }
}
