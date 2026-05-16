package com.example.market.application.port.`in`

import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.shared.UserId

/**
 * 예약 라이프사이클 명령 — 사용자 / 운영자 / 시스템 다른 actor 가 호출.
 *
 * `cancel` 은 셀러 본인만 (Trade.sellerId 매칭). 나머지 (`markArrived /
 * markCompleted / markRejected`) 는 검수센터 직원/검수원 — 권한 검사는 adapter-in
 * 의 `@PreAuthorize` 에서.
 */
interface InspectionAppointmentLifecycleUseCase {

    /** 셀러 본인 취소 (RESERVED → CANCELLED). 슬롯 자리 회수. */
    fun cancel(requestor: UserId, appointmentId: AppointmentId)

    /** 검수센터 직원이 셀러 도착 확인 (RESERVED → ARRIVED). */
    fun markArrived(appointmentId: AppointmentId)

    /** 검수원이 검수 통과 결정 (ARRIVED → COMPLETED). */
    fun markCompleted(appointmentId: AppointmentId)

    /** 검수원이 거부 결정 — 가품/손상 (ARRIVED → REJECTED). */
    fun markRejected(appointmentId: AppointmentId)
}
