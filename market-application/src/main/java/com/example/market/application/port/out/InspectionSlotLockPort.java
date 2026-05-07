package com.example.market.application.port.out;

import com.example.market.domain.inspection.scheduling.InspectionCenterId;

import java.time.Instant;

/**
 * 한 (center × slot) 단위 advisory lock — 동시 예약이 capacity 초과하지 않게 직렬화.
 *
 * <p>구현은 {@code pg_advisory_xact_lock(hash(centerId, slotStart))} — 같은 슬롯 동시
 * BookAppointment 가 들어와도 한 트랜잭션씩 직렬 처리. 트랜잭션 commit / rollback 과 함께 자동 해제.</p>
 *
 * <p>같은 패턴을 OrderBookQueryPort.acquireSkuLock 이 매칭에 사용 (ADR-0005). race 가 잦은
 * 곳마다 advisory lock 으로 deadlock 결정적 회피.</p>
 */
public interface InspectionSlotLockPort {

    void acquireSlotLock(InspectionCenterId centerId, Instant slotStart);
}
