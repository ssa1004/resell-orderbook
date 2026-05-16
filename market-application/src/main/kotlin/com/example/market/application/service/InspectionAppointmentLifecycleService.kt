package com.example.market.application.service

import com.example.market.application.exception.InspectionExceptions
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.InspectionAppointmentLifecycleUseCase
import com.example.market.application.port.out.InspectionAppointmentRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.shared.UserId
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class InspectionAppointmentLifecycleService(
    private val appointments: InspectionAppointmentRepository,
    private val trades: TradeRepository,
    private val clock: Clock,
) : InspectionAppointmentLifecycleUseCase {

    /**
     * 셀러 본인 취소만 허용. 다른 사용자가 호출하면
     * [InspectionExceptions.UnauthorizedAppointmentOperationException].
     */
    @Transactional
    override fun cancel(requestor: UserId, appointmentId: AppointmentId) {
        val a = load(appointmentId)
        val trade = trades.findById(a.tradeId)
            .orElseThrow { TradeNotFoundException(a.tradeId) }
        if (trade.sellerId != requestor) {
            throw InspectionExceptions.UnauthorizedAppointmentOperationException(
                appointmentId, requestor, "cancel",
            )
        }
        a.cancel(clock)
        appointments.save(a)
        log.info("appointment cancelled id={}", appointmentId)
    }

    @Transactional
    override fun markArrived(appointmentId: AppointmentId) {
        val a = load(appointmentId)
        a.markArrived(clock)
        appointments.save(a)
        log.info("appointment arrived id={}", appointmentId)
    }

    @Transactional
    override fun markCompleted(appointmentId: AppointmentId) {
        val a = load(appointmentId)
        a.markCompleted(clock)
        appointments.save(a)
        log.info("appointment completed id={}", appointmentId)
    }

    @Transactional
    override fun markRejected(appointmentId: AppointmentId) {
        val a = load(appointmentId)
        a.markRejected(clock)
        appointments.save(a)
        log.info("appointment rejected id={}", appointmentId)
    }

    private fun load(id: AppointmentId): InspectionAppointment {
        return appointments.findById(id)
            .orElseThrow { InspectionExceptions.AppointmentNotFoundException(id) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(InspectionAppointmentLifecycleService::class.java)
    }
}
