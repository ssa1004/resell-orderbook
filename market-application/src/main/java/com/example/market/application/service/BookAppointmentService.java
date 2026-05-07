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
 * 검수 예약 — 정원 초과 (over-booking) 방지가 핵심.
 *
 * <p><b>흐름</b> (한 트랜잭션 안에서 모두 처리):
 * <ol>
 *   <li>Idempotency-Key (같은 요청이 두 번 와도 한 번만 처리되게 막는 클라이언트 발급 키)
 *       점유 — 사용자 중복 클릭 방지</li>
 *   <li>Center 조회</li>
 *   <li>요청한 시각을 도메인 메서드가 슬롯 시작 시각으로 정렬 (예: 14:23 → 14:00)</li>
 *   <li>예약 가능 마감 시간(lead-time) 검사 — 너무 임박하면 거절</li>
 *   <li>같은 거래(trade)에 이미 활성 예약이 있는지 검사 — 있으면 거절 (중복 예약 방지)</li>
 *   <li><b>Slot advisory lock</b> — 같은 (센터, 슬롯) 조합에 대한 동시 시도를 한 줄로 줄세움</li>
 *   <li>그 슬롯의 현재 활성 예약 수를 세서 정원 미만이면 INSERT, 정원 초과면 SlotFullException</li>
 * </ol>
 *
 * <p><b>왜 advisory lock 이 필요한가</b>: SELECT COUNT 와 INSERT 사이에 경쟁 구간 (race
 * window) 이 생긴다. 두 사용자가 동시에 마지막 자리에 예약 시도 → 둘 다 COUNT 가 정원-1 →
 * 둘 다 INSERT → 정원 초과. advisory lock (PostgreSQL 의 임의 키에 거는 응용 락) 으로 같은
 * 슬롯에 대한 동시 시도를 한 번에 하나씩 처리해 경쟁을 차단한다. ADR-0005 의 매칭 동시성과
 * 같은 패턴.</p>
 *
 * <p><b>한 트랜잭션 안에서 끝남</b>: lock + COUNT + INSERT 가 모두 같은 트랜잭션. lock 은 커밋
 * 또는 롤백 시 자동 해제.</p>
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

        // 너무 임박한 슬롯은 거절 (검수원 준비 시간 + 셀러 이동 시간)
        if (center.isWithinLeadTime(slotStart, now)) {
            throw new InspectionExceptions.TooLateToBookException(centerId, slotStart);
        }

        // 같은 거래에 활성 예약이 이미 있나? (사용자 중복 클릭/실수 방지)
        var existing = appointments.findActiveByTrade(tradeId);
        if (!existing.isEmpty()) {
            throw new InspectionExceptions.AlreadyBookedException(tradeId, existing.get(0).id());
        }

        // 슬롯 단위 advisory lock — 같은 (센터, 슬롯) 동시 시도를 한 줄로 줄세움
        // (정원 초과 race 방지. 트랜잭션 종료 시 자동 해제)
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
