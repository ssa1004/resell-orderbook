package com.example.market.application.service;

import com.example.market.application.exception.InspectionExceptions;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.AppointmentStatus;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검수 예약 lifecycle 의 권한 검사 — cancel 은 거래 셀러 본인만 호출 가능.
 *
 * <p>{@code markArrived / markCompleted / markRejected} 는 운영자 전용으로
 * adapter-in 의 {@code @PreAuthorize} 가 1차 차단하므로 service 단에서는 별도 검사 없이
 * 도메인 상태 전이만 책임진다.</p>
 */
class InspectionAppointmentLifecycleServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UserId SELLER = UserId.of("seller-1");
    private static final UserId BUYER = UserId.of("buyer-1");
    private static final UserId STRANGER = UserId.of("evil-user");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private InspectionAppointmentRepository appointments;
    private TradeRepository trades;
    private InspectionAppointmentLifecycleService service;

    @BeforeEach
    void setUp() {
        appointments = mock(InspectionAppointmentRepository.class);
        trades = mock(TradeRepository.class);
        service = new InspectionAppointmentLifecycleService(appointments, trades, CLOCK);
    }

    @Test
    void cancelBySeller_succeeds() {
        Trade trade = aTrade();
        InspectionAppointment appt = anAppointment(trade.id());
        when(appointments.findById(appt.id())).thenReturn(Optional.of(appt));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        service.cancel(SELLER, appt.id());

        assertThat(appt.status()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointments).save(appt);
    }

    @Test
    void cancelByBuyer_throwsForbidden() {
        Trade trade = aTrade();
        InspectionAppointment appt = anAppointment(trade.id());
        when(appointments.findById(appt.id())).thenReturn(Optional.of(appt));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        assertThatThrownBy(() -> service.cancel(BUYER, appt.id()))
                .isInstanceOf(InspectionExceptions.UnauthorizedAppointmentOperationException.class);
        assertThat(appt.status()).isEqualTo(AppointmentStatus.RESERVED);
        verify(appointments, never()).save(any());
    }

    @Test
    void cancelByStranger_throwsForbidden() {
        Trade trade = aTrade();
        InspectionAppointment appt = anAppointment(trade.id());
        when(appointments.findById(appt.id())).thenReturn(Optional.of(appt));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        assertThatThrownBy(() -> service.cancel(STRANGER, appt.id()))
                .isInstanceOf(InspectionExceptions.UnauthorizedAppointmentOperationException.class);
        verify(appointments, never()).save(any());
    }

    @Test
    void cancel_appointmentMissing_throwsNotFound() {
        AppointmentId missing = AppointmentId.newId();
        when(appointments.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(SELLER, missing))
                .isInstanceOf(InspectionExceptions.AppointmentNotFoundException.class);
    }

    @Test
    void cancel_tradeMissing_throwsNotFound() {
        Trade trade = aTrade();
        InspectionAppointment appt = anAppointment(trade.id());
        when(appointments.findById(appt.id())).thenReturn(Optional.of(appt));
        when(trades.findById(trade.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(SELLER, appt.id()))
                .isInstanceOf(TradeNotFoundException.class);
        verify(appointments, never()).save(any());
    }

    private static Trade aTrade() {
        SkuId sku = SkuId.newId();
        Listing ask = Listing.place(sku, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(sku, BUYER, money(150_000), NOW);
        return Trade.match(ask, bid, money(150_000), POLICY, NOW);
    }

    private static InspectionAppointment anAppointment(TradeId tradeId) {
        return InspectionAppointment.book(tradeId, InspectionCenterId.newId(),
                Instant.parse("2026-05-05T14:00:00Z"),
                Instant.parse("2026-05-05T15:00:00Z"), CLOCK);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
