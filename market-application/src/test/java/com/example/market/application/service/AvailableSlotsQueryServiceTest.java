package com.example.market.application.service;

import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.application.port.out.InspectionCenterRepository;
import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvailableSlotsQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-08T00:00:00Z");
    private static final InspectionCenterId CENTER_ID = InspectionCenterId.newId();

    private InspectionCenterRepository centers;
    private InspectionAppointmentRepository appointments;
    private AvailableSlotsQueryService service;

    @BeforeEach
    void setUp() {
        centers = mock(InspectionCenterRepository.class);
        appointments = mock(InspectionAppointmentRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AvailableSlotsQueryService(centers, appointments, clock);

        InspectionCenter center = InspectionCenter.restore(
                CENTER_ID, "C1", "addr", 4, Duration.ofHours(1), Duration.ofHours(1), NOW);
        lenient().when(centers.findById(CENTER_ID)).thenReturn(Optional.of(center));
        lenient().when(appointments.countActiveInRange(any(), any(), any())).thenReturn(Map.of());
    }

    @Test
    void rejects_inverted_range() {
        Instant from = Instant.parse("2026-05-08T10:00:00Z");
        Instant to = from.minus(Duration.ofHours(1));

        assertThatThrownBy(() -> service.findSlots(CENTER_ID, from, to))
                .isInstanceOf(IllegalArgumentException.class);

        verify(centers, never()).findById(any());
    }

    @Test
    void rejects_range_above_max() {
        Instant from = Instant.parse("2026-05-08T00:00:00Z");
        Instant to = from.plus(AvailableSlotsQueryService.MAX_RANGE).plus(Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.findSlots(CENTER_ID, from, to))
                .isInstanceOf(IllegalArgumentException.class);

        verify(centers, never()).findById(any());
    }

    @Test
    void accepts_range_at_exactly_max() {
        Instant from = Instant.parse("2026-05-08T00:00:00Z");
        Instant to = from.plus(AvailableSlotsQueryService.MAX_RANGE);

        // 끝 포함 안 함 → 슬롯 walk 가 정상적으로 끝나야 한다
        service.findSlots(CENTER_ID, from, to);
        verify(centers).findById(CENTER_ID);
    }
}
