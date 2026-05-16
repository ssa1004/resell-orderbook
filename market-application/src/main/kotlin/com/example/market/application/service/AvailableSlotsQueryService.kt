package com.example.market.application.service

import com.example.market.application.exception.InspectionExceptions
import com.example.market.application.port.`in`.AvailableSlotsQueryUseCase
import com.example.market.application.port.out.InspectionAppointmentRepository
import com.example.market.application.port.out.InspectionCenterRepository
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.inspection.scheduling.SlotAvailability
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 캘린더 뷰 — `[from, to)` 안의 모든 슬롯의 capacity / 예약 가능 여부.
 *
 * 활성 카운트는 1개의 GROUP BY query 로 (모든 슬롯이 0인 경우엔 응답에서 0 으로 채움).
 * 슬롯이 많아도 query 1번이라 빠름.
 *
 * 요청 범위 상한 — 한 응답에 너무 많은 슬롯을 빌려고 가지 않게 31일을 한도로 둔다.
 * 캘린더 UI 에서 한 달 단위로 보는 정도가 자연스러운 호출 패턴이라 충분.
 */
@Service
@Transactional(readOnly = true)
open class AvailableSlotsQueryService(
    private val centers: InspectionCenterRepository,
    private val appointments: InspectionAppointmentRepository,
    private val clock: Clock,
) : AvailableSlotsQueryUseCase {

    override fun findSlots(centerId: InspectionCenterId, from: Instant, to: Instant): List<SlotAvailability> {
        require(from.isBefore(to)) { "from must be before to" }
        require(Duration.between(from, to) <= MAX_RANGE) {
            "range too wide — max ${MAX_RANGE.toDays()} days"
        }
        val center = centers.findById(centerId)
            .orElseThrow { InspectionExceptions.CenterNotFoundException(centerId) }
        val now = clock.instant()
        val counts: Map<Instant, Long> = appointments.countActiveInRange(centerId, from, to)

        val result = ArrayList<SlotAvailability>()
        // 슬롯들을 from 부터 to 직전까지 슬롯 단위로 walk
        var cursor = center.slotStartFor(from)
        if (cursor.isBefore(from)) {
            cursor = cursor.plus(center.slotDuration)
        }
        while (cursor.isBefore(to)) {
            val booked = counts.getOrDefault(cursor, 0L)
            val bookable = booked < center.parallelCapacity &&
                !center.isWithinLeadTime(cursor, now)
            result.add(
                SlotAvailability(
                    centerId,
                    cursor,
                    center.slotEndFor(cursor),
                    center.parallelCapacity,
                    booked.toInt(),
                    bookable,
                ),
            )
            cursor = cursor.plus(center.slotDuration)
        }
        return result
    }

    companion object {
        /**
         * 한 호출이 다룰 수 있는 최대 시간 범위. 기본 슬롯 60분 기준 약 744 슬롯 — 응답이 그 이상
         * 커지면 클라이언트가 페이지를 끊어 부르도록 강제한다.
         *
         * 원래 Java 코드에선 package-private 였으나 Kotlin 으로 옮기며 동일 모듈 내 Java
         * test 도 접근 가능하도록 `@JvmField` 로 노출.
         */
        @JvmField
        val MAX_RANGE: Duration = Duration.ofDays(31)
    }
}
