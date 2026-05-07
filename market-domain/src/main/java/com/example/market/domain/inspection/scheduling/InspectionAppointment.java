package com.example.market.domain.inspection.scheduling;

import com.example.market.domain.trading.TradeId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * 검수 예약 — 한 거래(Trade) × 한 검수센터(InspectionCenter) × 한 시간 슬롯의 묶음.
 *
 * <p>한 거래당 동시에 활성 (RESERVED 또는 ARRIVED) 인 예약은 1개만 — DB UNIQUE 제약
 * (DB 자체가 같은 키 중복을 거절하는 규칙) 이 (trade_id, status) 조합 (활성 상태일 때만)
 * 으로 보장한다. 사용자가 시간을 바꾸려면 (reschedule) 기존 예약을 취소한 뒤 새로 예약.</p>
 *
 * <p>상태 변경은 도메인 메서드를 통해서만 가능하다 — setter 없음.</p>
 */
public final class InspectionAppointment {

    private final AppointmentId id;
    private final TradeId tradeId;
    private final InspectionCenterId centerId;
    /** 예약된 슬롯 시작 시각. 슬롯 row 를 미리 만들지 않고 (centerId, slotStart) 튜플 자체를
     * 슬롯의 식별자로 사용한다 — 암묵적 슬롯. */
    private final Instant slotStart;
    private final Instant slotEnd;
    private AppointmentStatus status;
    private final Instant bookedAt;
    private Instant arrivedAt;
    private Instant completedAt;
    private long version;

    private InspectionAppointment(AppointmentId id, TradeId tradeId, InspectionCenterId centerId,
                                  Instant slotStart, Instant slotEnd, AppointmentStatus status,
                                  Instant bookedAt, Instant arrivedAt, Instant completedAt,
                                  long version) {
        this.id = id;
        this.tradeId = tradeId;
        this.centerId = centerId;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.status = status;
        this.bookedAt = bookedAt;
        this.arrivedAt = arrivedAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    public static InspectionAppointment book(TradeId tradeId, InspectionCenterId centerId,
                                             Instant slotStart, Instant slotEnd, Clock clock) {
        Objects.requireNonNull(tradeId);
        Objects.requireNonNull(centerId);
        Objects.requireNonNull(slotStart);
        Objects.requireNonNull(slotEnd);
        if (!slotEnd.isAfter(slotStart)) {
            throw new IllegalArgumentException("slotEnd must be after slotStart");
        }
        return new InspectionAppointment(AppointmentId.newId(), tradeId, centerId,
                slotStart, slotEnd, AppointmentStatus.RESERVED,
                clock.instant(), null, null, 0L);
    }

    public static InspectionAppointment restore(AppointmentId id, TradeId tradeId,
                                                InspectionCenterId centerId,
                                                Instant slotStart, Instant slotEnd,
                                                AppointmentStatus status, Instant bookedAt,
                                                Instant arrivedAt, Instant completedAt,
                                                long version) {
        return new InspectionAppointment(id, tradeId, centerId, slotStart, slotEnd,
                status, bookedAt, arrivedAt, completedAt, version);
    }

    /** 셀러가 검수센터에 매물을 가져옴. 검수원이 받기 시작. */
    public void markArrived(Clock clock) {
        if (status != AppointmentStatus.RESERVED) {
            throw new IllegalStateException("only RESERVED can be marked ARRIVED: " + status);
        }
        this.status = AppointmentStatus.ARRIVED;
        this.arrivedAt = clock.instant();
    }

    /** 검수 통과 — Trade 도 다음 단계로 진행. */
    public void markCompleted(Clock clock) {
        if (status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException("only ARRIVED can be marked COMPLETED: " + status);
        }
        this.status = AppointmentStatus.COMPLETED;
        this.completedAt = clock.instant();
    }

    /** 검수 거부 — 가품 또는 손상 등. 이후 Trade 는 환불 흐름으로 진행. */
    public void markRejected(Clock clock) {
        if (status != AppointmentStatus.ARRIVED) {
            throw new IllegalStateException("only ARRIVED can be marked REJECTED: " + status);
        }
        this.status = AppointmentStatus.REJECTED;
        this.completedAt = clock.instant();
    }

    /** 셀러 자발적 취소 — 슬롯 회수. */
    public void cancel(Clock clock) {
        if (status != AppointmentStatus.RESERVED) {
            throw new IllegalStateException("only RESERVED can be cancelled: " + status);
        }
        this.status = AppointmentStatus.CANCELLED;
        this.completedAt = clock.instant();
    }

    /** 약속 시간 + 유예 시간(grace period)이 지나도 셀러가 안 오는 경우 — 배치가 처리해
     * 슬롯 자리를 다른 사람이 쓸 수 있도록 회수한다. */
    public void markNoShow(Clock clock) {
        if (status != AppointmentStatus.RESERVED) {
            throw new IllegalStateException("only RESERVED can be marked NO_SHOW: " + status);
        }
        this.status = AppointmentStatus.NO_SHOW;
        this.completedAt = clock.instant();
    }

    // Getters
    public AppointmentId id() { return id; }
    public TradeId tradeId() { return tradeId; }
    public InspectionCenterId centerId() { return centerId; }
    public Instant slotStart() { return slotStart; }
    public Instant slotEnd() { return slotEnd; }
    public AppointmentStatus status() { return status; }
    public Instant bookedAt() { return bookedAt; }
    public Instant arrivedAt() { return arrivedAt; }
    public Instant completedAt() { return completedAt; }
    public long version() { return version; }
}
