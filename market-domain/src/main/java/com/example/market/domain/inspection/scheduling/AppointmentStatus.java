package com.example.market.domain.inspection.scheduling;

/**
 * 검수 예약 상태.
 *
 * <pre>
 *   RESERVED ── 셀러가 검수센터 도착 ── ARRIVED ── 검수원이 검수 진행 ──▶ COMPLETED
 *      │                                  │
 *      │                                  └── 검수 후 거부/반품 ──▶ REJECTED
 *      │
 *      ├── 셀러 본인 취소 ──▶ CANCELLED
 *      │
 *      └── 약속 시간 + grace 지나도 미도착 ──▶ NO_SHOW   (batch 자동 처리)
 * </pre>
 *
 * <p>RESERVED 와 ARRIVED 만 "센터 capacity 점유" — NO_SHOW / CANCELLED 는 자리 회수.
 * COMPLETED / REJECTED 는 이미 검수 진행이 끝났으므로 자리 free 한 것과 동일.</p>
 */
public enum AppointmentStatus {
    RESERVED,
    ARRIVED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    NO_SHOW;

    public boolean isOccupyingCapacity() {
        return this == RESERVED || this == ARRIVED;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == CANCELLED || this == NO_SHOW;
    }
}
