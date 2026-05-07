package com.example.market.application.service;

import com.example.market.application.exception.InspectionExceptions;
import com.example.market.application.port.in.InspectionAppointmentLifecycleUseCase;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
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
    private final Clock clock;

    @Override
    @Transactional
    public void cancel(AppointmentId id) {
        InspectionAppointment a = load(id);
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
