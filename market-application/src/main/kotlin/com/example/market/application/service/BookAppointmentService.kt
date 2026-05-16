package com.example.market.application.service

import com.example.market.application.command.BookAppointmentCommand
import com.example.market.application.exception.InspectionExceptions
import com.example.market.application.port.`in`.BookAppointmentUseCase
import com.example.market.application.port.out.InspectionAppointmentRepository
import com.example.market.application.port.out.InspectionCenterRepository
import com.example.market.application.port.out.InspectionSlotLockPort
import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.trading.TradeId
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 검수 예약 — 정원 초과 (over-booking) 방지가 핵심.
 *
 * **흐름** (한 트랜잭션 안에서 모두 처리):
 * 1. Idempotency-Key (같은 요청이 두 번 와도 한 번만 처리되게 막는 클라이언트 발급 키)
 *    점유 — 사용자 중복 클릭 방지
 * 2. Center 조회
 * 3. 요청한 시각을 도메인 메서드가 슬롯 시작 시각으로 정렬 (예: 14:23 → 14:00)
 * 4. 예약 가능 마감 시간(lead-time) 검사 — 너무 임박하면 거절
 * 5. 같은 거래(trade)에 이미 활성 예약이 있는지 검사 — 있으면 거절 (중복 예약 방지)
 * 6. **Slot advisory lock** — 같은 (센터, 슬롯) 조합에 대한 동시 시도를 한 줄로 줄세움
 * 7. 그 슬롯의 현재 활성 예약 수를 세서 정원 미만이면 INSERT, 정원 초과면 SlotFullException
 *
 * **왜 advisory lock 이 필요한가**: SELECT COUNT 와 INSERT 사이에 경쟁 구간 (race
 * window) 이 생긴다. 두 사용자가 동시에 마지막 자리에 예약 시도 → 둘 다 COUNT 가 정원-1 →
 * 둘 다 INSERT → 정원 초과. advisory lock (PostgreSQL 의 임의 키에 거는 응용 락) 으로 같은
 * 슬롯에 대한 동시 시도를 한 번에 하나씩 처리해 경쟁을 차단한다. ADR-0005 의 매칭 동시성과
 * 같은 패턴.
 *
 * **한 트랜잭션 안에서 끝남**: lock + COUNT + INSERT 가 모두 같은 트랜잭션. lock 은 커밋
 * 또는 롤백 시 자동 해제.
 */
@Service
open class BookAppointmentService(
    private val centers: InspectionCenterRepository,
    private val appointments: InspectionAppointmentRepository,
    private val slotLock: InspectionSlotLockPort,
    private val idempotency: IdempotentExecution,
    private val clock: Clock,
) : BookAppointmentUseCase {

    @Transactional
    override fun book(command: BookAppointmentCommand): InspectionAppointment {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)

        val centerId = InspectionCenterId(command.centerId)
        val tradeId = TradeId.of(command.tradeId.toString())
        val center = centers.findById(centerId)
            .orElseThrow { InspectionExceptions.CenterNotFoundException(centerId) }

        val slotStart = center.slotStartFor(command.desiredSlotTime)
        val slotEnd = center.slotEndFor(slotStart)
        val now = clock.instant()

        // 너무 임박한 슬롯은 거절 (검수원 준비 시간 + 셀러 이동 시간)
        if (center.isWithinLeadTime(slotStart, now)) {
            throw InspectionExceptions.TooLateToBookException(centerId, slotStart)
        }

        // 같은 거래에 활성 예약이 이미 있나? (사용자 중복 클릭/실수 방지)
        val existing = appointments.findActiveByTrade(tradeId)
        if (existing.isNotEmpty()) {
            throw InspectionExceptions.AlreadyBookedException(tradeId, existing[0].id)
        }

        // 슬롯 단위 advisory lock — 같은 (센터, 슬롯) 동시 시도를 한 줄로 줄세움
        // (정원 초과 race 방지. 트랜잭션 종료 시 자동 해제)
        slotLock.acquireSlotLock(centerId, slotStart)

        val active = appointments.countActive(centerId, slotStart)
        if (active >= center.parallelCapacity) {
            throw InspectionExceptions.SlotFullException(
                centerId, slotStart, center.parallelCapacity.toLong(),
            )
        }

        val appointment = InspectionAppointment.book(
            tradeId, centerId, slotStart, slotEnd, clock,
        )
        appointments.save(appointment)
        log.info(
            "appointment booked id={} trade={} center={} slot={} {}/{}",
            appointment.id, tradeId, centerId, slotStart,
            active + 1, center.parallelCapacity,
        )
        return appointment
    }

    companion object {
        private val log = LoggerFactory.getLogger(BookAppointmentService::class.java)
    }
}
