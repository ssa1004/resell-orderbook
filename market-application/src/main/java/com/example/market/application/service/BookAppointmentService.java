package com.example.market.application.service;

import com.example.market.application.command.BookAppointmentCommand;
import com.example.market.application.exception.InspectionExceptions;
import com.example.market.application.port.in.BookAppointmentUseCase;
import com.example.market.application.port.out.IdempotencyKeyStore;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.application.port.out.InspectionCenterRepository;
import com.example.market.application.port.out.InspectionSlotLockPort;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.trading.TradeId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 검수 예약 — over-booking 방지가 핵심.
 *
 * <p><b>흐름</b> (한 트랜잭션):
 * <ol>
 *   <li>Idempotency-Key 점유 — 사용자 중복 클릭 방지</li>
 *   <li>Center 조회</li>
 *   <li>desiredSlotTime → 도메인 메서드로 slotStart 정렬 (예: 14:23 → 14:00)</li>
 *   <li>booking lead-time 검사 (너무 임박하면 거절)</li>
 *   <li>한 trade 가 이미 active 예약 있는지 — 있으면 거절 (중복 예약 방지)</li>
 *   <li><b>Slot advisory lock</b> — 같은 (center × slot) 동시 예약 직렬화</li>
 *   <li>현재 활성 카운트 조회 → capacity 미만이면 INSERT, 초과면 SlotFullException</li>
 * </ol>
 *
 * <p><b>왜 advisory lock 이 필요한가</b>: SELECT COUNT + INSERT 사이에 race window. 두 사용자가
 * 동시에 마지막 자리에 예약 시도 → 둘 다 COUNT 가 capacity-1 → 둘 다 INSERT → over-booking.
 * advisory lock 이 같은 슬롯 동시 시도를 *한 번에 하나씩* 처리해 race 차단. ADR-0005 와 같은 패턴.</p>
 *
 * <p><b>한 트랜잭션 안</b>: lock + COUNT + INSERT 모두 같은 트랜잭션. lock 은 commit 시 자동 해제.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookAppointmentService implements BookAppointmentUseCase {

    private final InspectionCenterRepository centers;
    private final InspectionAppointmentRepository appointments;
    private final InspectionSlotLockPort slotLock;
    private final IdempotencyKeyStore idempotencyKeys;
    private final Clock clock;

    @Override
    @Transactional
    public InspectionAppointment book(BookAppointmentCommand cmd) {
        idempotencyKeys.acquireOrThrow(cmd.idempotencyKey());

        InspectionCenterId centerId = new InspectionCenterId(cmd.centerId());
        TradeId tradeId = TradeId.of(cmd.tradeId().toString());
        InspectionCenter center = centers.findById(centerId)
                .orElseThrow(() -> new InspectionExceptions.CenterNotFoundException(centerId));

        Instant slotStart = center.slotStartFor(cmd.desiredSlotTime());
        Instant slotEnd = center.slotEndFor(slotStart);
        Instant now = clock.instant();

        // 너무 임박한 슬롯은 거절 (검수원 준비 시간)
        if (center.isWithinLeadTime(slotStart, now)) {
            throw new InspectionExceptions.TooLateToBookException(centerId, slotStart);
        }

        // 같은 trade 가 이미 active 예약 있나?
        var existing = appointments.findActiveByTrade(tradeId);
        if (!existing.isEmpty()) {
            throw new InspectionExceptions.AlreadyBookedException(tradeId, existing.get(0).id());
        }

        // *Slot lock* — 같은 (center × slot) 동시 시도 직렬화
        slotLock.acquireSlotLock(centerId, slotStart);

        long active = appointments.countActive(centerId, slotStart);
        if (active >= center.parallelCapacity()) {
            throw new InspectionExceptions.SlotFullException(centerId, slotStart,
                    center.parallelCapacity());
        }

        InspectionAppointment appointment = InspectionAppointment.book(
                tradeId, centerId, slotStart, slotEnd, clock);
        appointments.save(appointment);
        log.info("appointment booked id={} trade={} center={} slot={} {}/{}",
                appointment.id(), tradeId, centerId, slotStart,
                active + 1, center.parallelCapacity());
        return appointment;
    }
}
