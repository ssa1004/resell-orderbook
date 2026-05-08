package com.example.market.application.port.in;

import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.shared.UserId;

/**
 * 예약 라이프사이클 명령 — 사용자 / 운영자 / 시스템 다른 actor 가 호출.
 *
 * <p>{@code cancel} 은 셀러 본인만 (Trade.sellerId 매칭). 나머지 ({@code markArrived /
 * markCompleted / markRejected}) 는 검수센터 직원/검수원 — 권한 검사는 adapter-in
 * 의 {@code @PreAuthorize} 에서.</p>
 */
public interface InspectionAppointmentLifecycleUseCase {

    /** 셀러 본인 취소 (RESERVED → CANCELLED). 슬롯 자리 회수. */
    void cancel(UserId requestor, AppointmentId appointmentId);

    /** 검수센터 직원이 셀러 도착 확인 (RESERVED → ARRIVED). */
    void markArrived(AppointmentId appointmentId);

    /** 검수원이 검수 통과 결정 (ARRIVED → COMPLETED). */
    void markCompleted(AppointmentId appointmentId);

    /** 검수원이 거부 결정 — 가품/손상 (ARRIVED → REJECTED). */
    void markRejected(AppointmentId appointmentId);
}
