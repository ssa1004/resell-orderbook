package com.example.market.application.exception

import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import java.time.Instant

/**
 * Inspection scheduling 도메인 application exception 들. 한 파일에 모음 — 작은 사이즈라 별도 파일 비효율.
 */
object InspectionExceptions {

    class CenterNotFoundException(id: InspectionCenterId) :
        RuntimeException("inspection center not found: $id")

    class AppointmentNotFoundException(id: AppointmentId) :
        RuntimeException("inspection appointment not found: $id")

    /**
     * 예약 lifecycle 메서드를 호출한 사용자가 해당 거래의 셀러가 아니거나, 운영자/검수원
     * 권한이 없을 때. Adapter-in 이 HTTP 403 으로 매핑.
     */
    class UnauthorizedAppointmentOperationException(
        appointmentId: AppointmentId,
        requestor: UserId,
        op: String,
    ) : RuntimeException(
        "appointment $appointmentId — requestor $requestor not authorized for $op"
    )

    /** 슬롯 capacity 초과 — 다른 사용자가 먼저 예약. */
    class SlotFullException(centerId: InspectionCenterId, slotStart: Instant, capacity: Long) :
        RuntimeException("slot full: center=$centerId slot=$slotStart capacity=$capacity")

    /** 예약 마감 시간 (lead time) 안 — 너무 임박해 예약 불가. */
    class TooLateToBookException(centerId: InspectionCenterId, slotStart: Instant) :
        RuntimeException("too late to book: center=$centerId slot=$slotStart")

    /** 한 trade 가 이미 active 예약을 가지고 있음 — 중복 예약 방지. */
    class AlreadyBookedException(tradeId: TradeId, existing: AppointmentId) :
        RuntimeException("trade already has active appointment: trade=$tradeId existing=$existing")
}
