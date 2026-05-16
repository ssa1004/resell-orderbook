package com.example.market.application.port.out

import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.trading.TradeId
import java.time.Instant
import java.util.Optional

interface InspectionAppointmentRepository {

    fun save(appointment: InspectionAppointment)

    fun findById(id: AppointmentId): Optional<InspectionAppointment>

    /**
     * 한 (center × slot) 의 *capacity 점유 중* 예약 수. BookAppointmentService 가 advisory
     * lock 잡고 이 값으로 capacity 검사 → INSERT 결정.
     */
    fun countActive(centerId: InspectionCenterId, slotStart: Instant): Long

    /** 한 시간 구간 내 슬롯별 카운트. AvailableSlotsQuery 가 사용. */
    fun countActiveInRange(centerId: InspectionCenterId, from: Instant, to: Instant): Map<Instant, Long>

    /** 한 trade 의 active 예약 — 중복 예약 방지. */
    fun findActiveByTrade(tradeId: TradeId): List<InspectionAppointment>
}
