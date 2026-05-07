package com.example.market.application.exception;

import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.trading.TradeId;

import java.time.Instant;

/**
 * Inspection scheduling 도메인 application exception 들. 한 파일에 모음 — 작은 사이즈라 별도 파일 비효율.
 */
public final class InspectionExceptions {

    private InspectionExceptions() {}

    public static class CenterNotFoundException extends RuntimeException {
        public CenterNotFoundException(InspectionCenterId id) {
            super("inspection center not found: " + id);
        }
    }

    public static class AppointmentNotFoundException extends RuntimeException {
        public AppointmentNotFoundException(AppointmentId id) {
            super("inspection appointment not found: " + id);
        }
    }

    /** 슬롯 capacity 초과 — 다른 사용자가 먼저 예약. */
    public static class SlotFullException extends RuntimeException {
        public SlotFullException(InspectionCenterId centerId, Instant slotStart,
                                  long capacity) {
            super("slot full: center=" + centerId + " slot=" + slotStart + " capacity=" + capacity);
        }
    }

    /** 예약 마감 시간 (lead time) 안 — 너무 임박해 예약 불가. */
    public static class TooLateToBookException extends RuntimeException {
        public TooLateToBookException(InspectionCenterId centerId, Instant slotStart) {
            super("too late to book: center=" + centerId + " slot=" + slotStart);
        }
    }

    /** 한 trade 가 이미 active 예약을 가지고 있음 — 중복 예약 방지. */
    public static class AlreadyBookedException extends RuntimeException {
        public AlreadyBookedException(TradeId tradeId, AppointmentId existing) {
            super("trade already has active appointment: trade=" + tradeId + " existing=" + existing);
        }
    }
}
