package com.example.market.application.service;

import com.example.market.application.command.BookAppointmentCommand;
import com.example.market.application.exception.InspectionExceptions;
import com.example.market.application.port.out.IdempotencyKeyStore;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.application.port.out.InspectionCenterRepository;
import com.example.market.application.port.out.InspectionSlotLockPort;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookAppointmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock InspectionCenterRepository centers;
    @Mock InspectionAppointmentRepository appointments;
    @Mock InspectionSlotLockPort slotLock;
    @Mock IdempotencyKeyStore idempotencyKeys;

    BookAppointmentService service;

    @BeforeEach
    void setUp() {
        service = new BookAppointmentService(centers, appointments, slotLock, idempotencyKeys, CLOCK);
    }

    private static InspectionCenter aCenter(int capacity) {
        return InspectionCenter.restore(InspectionCenterId.newId(),
                "Seoul", "Gangnam", capacity,
                Duration.ofHours(1), Duration.ofMinutes(30), NOW.minusSeconds(86400));
    }

    private static BookAppointmentCommand cmd(InspectionCenter c, Instant desired) {
        return new BookAppointmentCommand("k-1", UUID.randomUUID(), c.id().value(), desired);
    }

    @Test
    void capacityNotReached_savesAppointment() {
        InspectionCenter center = aCenter(3);
        when(centers.findById(center.id())).thenReturn(Optional.of(center));
        when(appointments.findActiveByTrade(any(TradeId.class))).thenReturn(List.of());
        when(appointments.countActive(eq(center.id()), any(Instant.class))).thenReturn(2L);

        InspectionAppointment a = service.book(cmd(center, Instant.parse("2026-05-04T14:00:00Z")));

        assertThat(a).isNotNull();
        verify(slotLock).acquireSlotLock(eq(center.id()), eq(Instant.parse("2026-05-04T14:00:00Z")));
        verify(appointments).save(any(InspectionAppointment.class));
    }

    @Test
    void capacityReached_throwsSlotFull() {
        InspectionCenter center = aCenter(3);
        when(centers.findById(center.id())).thenReturn(Optional.of(center));
        when(appointments.findActiveByTrade(any(TradeId.class))).thenReturn(List.of());
        when(appointments.countActive(eq(center.id()), any(Instant.class))).thenReturn(3L);   // FULL

        assertThatThrownBy(() -> service.book(cmd(center, Instant.parse("2026-05-04T14:00:00Z"))))
                .isInstanceOf(InspectionExceptions.SlotFullException.class);
        verify(appointments, never()).save(any());
    }

    @Test
    void tooLateToBook_throws() {
        InspectionCenter center = aCenter(3);
        when(centers.findById(center.id())).thenReturn(Optional.of(center));

        // NOW 부터 20분 후 슬롯 — lead time(30분) 안
        assertThatThrownBy(() -> service.book(cmd(center, NOW.plus(Duration.ofMinutes(20)))))
                .isInstanceOf(InspectionExceptions.TooLateToBookException.class);
        verify(slotLock, never()).acquireSlotLock(any(), any());
    }

    @Test
    void alreadyBookedTrade_throws() {
        InspectionCenter center = aCenter(3);
        when(centers.findById(center.id())).thenReturn(Optional.of(center));
        InspectionAppointment existing = InspectionAppointment.book(
                TradeId.of(UUID.randomUUID().toString()), center.id(),
                Instant.parse("2026-05-04T16:00:00Z"),
                Instant.parse("2026-05-04T17:00:00Z"), CLOCK);
        when(appointments.findActiveByTrade(any(TradeId.class))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.book(cmd(center, Instant.parse("2026-05-04T14:00:00Z"))))
                .isInstanceOf(InspectionExceptions.AlreadyBookedException.class);
    }

    @Test
    void centerNotFound_throws() {
        InspectionCenter center = aCenter(3);
        when(centers.findById(center.id())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.book(cmd(center, Instant.parse("2026-05-04T14:00:00Z"))))
                .isInstanceOf(InspectionExceptions.CenterNotFoundException.class);
    }

    @Test
    void bookingAlignsToSlotStart() {
        InspectionCenter center = aCenter(3);
        when(centers.findById(center.id())).thenReturn(Optional.of(center));
        when(appointments.findActiveByTrade(any(TradeId.class))).thenReturn(List.of());
        when(appointments.countActive(eq(center.id()), any(Instant.class))).thenReturn(0L);

        // 14:23:45 요청 → 14:00:00 슬롯으로 정렬
        InspectionAppointment a = service.book(cmd(center, Instant.parse("2026-05-04T14:23:45Z")));
        assertThat(a.slotStart()).isEqualTo(Instant.parse("2026-05-04T14:00:00Z"));
        assertThat(a.slotEnd()).isEqualTo(Instant.parse("2026-05-04T15:00:00Z"));
    }
}
