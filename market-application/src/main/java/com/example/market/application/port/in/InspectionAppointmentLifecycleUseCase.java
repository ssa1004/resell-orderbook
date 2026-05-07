package com.example.market.application.port.in;

import com.example.market.domain.inspection.scheduling.AppointmentId;

/**
 * 예약 라이프사이클 명령 — 사용자 / 운영자 / 시스템 다른 actor 가 호출.
 */
public interface InspectionAppointmentLifecycleUseCase {

    /** 셀러 본인 취소 (RESERVED → CANCELLED). 슬롯 자리 회수. */
    void cancel(AppointmentId appointmentId);

    /** 검수센터 직원이 셀러 도착 확인 (RESERVED → ARRIVED). */
    void markArrived(AppointmentId appointmentId);

    /** 검수원이 검수 통과 결정 (ARRIVED → COMPLETED). */
    void markCompleted(AppointmentId appointmentId);

    /** 검수원이 거부 결정 — 가품/손상 (ARRIVED → REJECTED). */
    void markRejected(AppointmentId appointmentId);
}
