package com.example.market.domain.inspection.scheduling

import java.time.Instant

/**
 * 한 슬롯의 예약 가능 여부 + 잔여 capacity (read model).
 *
 * <p>화면에서 "9월 5일 15:00 슬롯에 X명 예약 가능" 같은 표시. 운영자도 이걸로 capacity
 * 사용률 모니터링.</p>
 */
data class SlotAvailability(
    @get:JvmName("centerId") val centerId: InspectionCenterId,
    @get:JvmName("slotStart") val slotStart: Instant,
    @get:JvmName("slotEnd") val slotEnd: Instant,
    @get:JvmName("totalCapacity") val totalCapacity: Int,
    @get:JvmName("bookedCount") val bookedCount: Int,
    @get:JvmName("bookable") val bookable: Boolean,    // capacity 남아 있고 + booking lead-time 안에 있지 않음
) {

    init {
        require(totalCapacity >= 0 && bookedCount >= 0) {
            "capacity / booked must be non-negative"
        }
        require(bookedCount <= totalCapacity) {
            "bookedCount ($bookedCount) > totalCapacity ($totalCapacity) — over-booking detected"
        }
    }

    fun remaining(): Int = totalCapacity - bookedCount
}
