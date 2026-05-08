package com.example.market.application.service;

import com.example.market.application.exception.InspectionExceptions;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.InspectionAppointmentLifecycleUseCase;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class InspectionAppointmentLifecycleService implements InspectionAppointmentLifecycleUseCase {

    private final InspectionAppointmentRepository appointments;
    private final TradeRepository trades;
    private final Clock clock;

    /**
     * 셀러 본인 취소만 허용. 다른 사용자가 호출하면
     * {@link InspectionExceptions.UnauthorizedAppointmentOperationException}.
     */
    @Override
    @Transactional
    public void cancel(UserId requestor, AppointmentId id) {
        InspectionAppointment a = load(id);
        Trade trade = trades.findById(a.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(a.tradeId()));
        if (!trade.sellerId().equals(requestor)) {
            throw new InspectionExceptions.UnauthorizedAppointmentOperationException(
                    id, requestor, "cancel");
        }
        a.cancel(clock);
        appointments.save(a);
        log.info("appointment cancelled id={}", id);
    }

    @Override
    @Transactional
    public void markArrived(AppointmentId id) {
        InspectionAppointment a = load(id);
        a.markArrived(clock);
        appointments.save(a);
        log.info("appointment arrived id={}", id);
    }

    @Override
    @Transactional
    public void markCompleted(AppointmentId id) {
        InspectionAppointment a = load(id);
        a.markCompleted(clock);
        appointments.save(a);
        log.info("appointment completed id={}", id);
    }

    @Override
    @Transactional
    public void markRejected(AppointmentId id) {
        InspectionAppointment a = load(id);
        a.markRejected(clock);
        appointments.save(a);
        log.info("appointment rejected id={}", id);
    }

    private InspectionAppointment load(AppointmentId id) {
        return appointments.findById(id)
                .orElseThrow(() -> new InspectionExceptions.AppointmentNotFoundException(id));
    }
}
