package com.example.market.domain.inspection.scheduling

import com.example.market.domain.trading.TradeId
import java.time.Clock
import java.time.Instant

/**
 * 검수 예약 — 한 거래(Trade) × 한 검수센터(InspectionCenter) × 한 시간 슬롯의 묶음.
 *
 * <p>한 거래당 동시에 활성 (RESERVED 또는 ARRIVED) 인 예약은 1개만 — DB UNIQUE 제약
 * (DB 자체가 같은 키 중복을 거절하는 규칙) 이 (trade_id, status) 조합 (활성 상태일 때만)
 * 으로 보장한다. 사용자가 시간을 바꾸려면 (reschedule) 기존 예약을 취소한 뒤 새로 예약.</p>
 *
 * <p>상태 변경은 도메인 메서드를 통해서만 가능하다 — setter 없음.</p>
 */
class InspectionAppointment private constructor(
    @get:JvmName("id") val id: AppointmentId,
    @get:JvmName("tradeId") val tradeId: TradeId,
    @get:JvmName("centerId") val centerId: InspectionCenterId,
    /**
     * 예약된 슬롯 시작 시각. 슬롯 row 를 미리 만들지 않고 (centerId, slotStart) 튜플 자체를
     * 슬롯의 식별자로 사용한다 — 암묵적 슬롯.
     */
    @get:JvmName("slotStart") val slotStart: Instant,
    @get:JvmName("slotEnd") val slotEnd: Instant,
    status: AppointmentStatus,
    @get:JvmName("bookedAt") val bookedAt: Instant,
    arrivedAt: Instant?,
    completedAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: AppointmentStatus = status
        private set

    @get:JvmName("arrivedAt")
    var arrivedAt: Instant? = arrivedAt
        private set

    @get:JvmName("completedAt")
    var completedAt: Instant? = completedAt
        private set

    /** 셀러가 검수센터에 매물을 가져옴. 검수원이 받기 시작. */
    fun markArrived(clock: Clock) {
        check(status == AppointmentStatus.RESERVED) {
            "only RESERVED can be marked ARRIVED: $status"
        }
        status = AppointmentStatus.ARRIVED
        arrivedAt = clock.instant()
    }

    /** 검수 통과 — Trade 도 다음 단계로 진행. */
    fun markCompleted(clock: Clock) {
        check(status == AppointmentStatus.ARRIVED) {
            "only ARRIVED can be marked COMPLETED: $status"
        }
        status = AppointmentStatus.COMPLETED
        completedAt = clock.instant()
    }

    /** 검수 거부 — 가품 또는 손상 등. 이후 Trade 는 환불 흐름으로 진행. */
    fun markRejected(clock: Clock) {
        check(status == AppointmentStatus.ARRIVED) {
            "only ARRIVED can be marked REJECTED: $status"
        }
        status = AppointmentStatus.REJECTED
        completedAt = clock.instant()
    }

    /** 셀러 자발적 취소 — 슬롯 회수. */
    fun cancel(clock: Clock) {
        check(status == AppointmentStatus.RESERVED) {
            "only RESERVED can be cancelled: $status"
        }
        status = AppointmentStatus.CANCELLED
        completedAt = clock.instant()
    }

    /**
     * 약속 시간 + 유예 시간(grace period)이 지나도 셀러가 안 오는 경우 — 배치가 처리해
     * 슬롯 자리를 다른 사람이 쓸 수 있도록 회수한다.
     */
    fun markNoShow(clock: Clock) {
        check(status == AppointmentStatus.RESERVED) {
            "only RESERVED can be marked NO_SHOW: $status"
        }
        status = AppointmentStatus.NO_SHOW
        completedAt = clock.instant()
    }

    companion object {
        @JvmStatic
        fun book(
            tradeId: TradeId,
            centerId: InspectionCenterId,
            slotStart: Instant,
            slotEnd: Instant,
            clock: Clock,
        ): InspectionAppointment {
            require(slotEnd.isAfter(slotStart)) { "slotEnd must be after slotStart" }
            return InspectionAppointment(
                id = AppointmentId.newId(),
                tradeId = tradeId,
                centerId = centerId,
                slotStart = slotStart,
                slotEnd = slotEnd,
                status = AppointmentStatus.RESERVED,
                bookedAt = clock.instant(),
                arrivedAt = null,
                completedAt = null,
                version = 0L,
            )
        }

        @JvmStatic
        fun restore(
            id: AppointmentId,
            tradeId: TradeId,
            centerId: InspectionCenterId,
            slotStart: Instant,
            slotEnd: Instant,
            status: AppointmentStatus,
            bookedAt: Instant,
            arrivedAt: Instant?,
            completedAt: Instant?,
            version: Long,
        ): InspectionAppointment = InspectionAppointment(
            id = id,
            tradeId = tradeId,
            centerId = centerId,
            slotStart = slotStart,
            slotEnd = slotEnd,
            status = status,
            bookedAt = bookedAt,
            arrivedAt = arrivedAt,
            completedAt = completedAt,
            version = version,
        )
    }
}
