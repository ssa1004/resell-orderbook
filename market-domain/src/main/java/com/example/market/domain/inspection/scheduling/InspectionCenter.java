package com.example.market.domain.inspection.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 검수 센터 — 셀러가 매물을 가져와 검수받는 실제 장소.
 *
 * <p>한 센터는 동시에 N건 검수가 가능하다 ({@code parallelCapacity} = 검수원 수). 한 시간 슬롯
 * (보통 1시간) 안에 그 이상 예약하려고 하면 거절한다. 슬롯 row 를 미리 INSERT 하지 않고,
 * (centerId, slotStart) 튜플 자체가 슬롯의 식별자 역할을 한다 (암묵적 슬롯). 예약 시
 * PostgreSQL 의 advisory lock (임의의 키에 거는 응용 락) 으로 동시 예약 충돌을 막고, 같은
 * 슬롯의 활성 예약 (RESERVED/ARRIVED) 수를 세서 정원(capacity) 을 검사한다.</p>
 *
 * <p><b>왜 슬롯을 미리 만들지 않나</b>: 슬롯 = (center, time) 조합인데, time 은 연속적인 값.
 * 매일 배치로 향후 30일치 슬롯 row 를 미리 만들어두면 행 폭증 + 정리 배치도 추가로 필요해진다.
 * 예약이 들어올 때마다 그 시각의 슬롯을 즉석으로 조회하는 편이 단순. 동시 예약 경쟁은
 * PostgreSQL advisory lock 으로 막는다 (ADR-0005 의 매칭 동시성 패턴과 같은 방식).</p>
 *
 * <p>운영 시간/휴일/시간대별 동적 capacity 같은 기능은 이 모델의 후속 작업 (별도 ADR). 현재는
 * 센터가 항상 열려 있고 정원은 정수 한 값으로 고정이라고 가정.</p>
 */
public final class InspectionCenter {

    private final InspectionCenterId id;
    private final String name;
    private final String address;
    /** 동시 검수 가능 인원 — 한 슬롯 안에서 활성 (RESERVED + ARRIVED) 예약의 최대 수. */
    private final int parallelCapacity;
    /** 한 슬롯의 길이. 보통 60분. 짧을수록 시간을 정확하게 잡지만, 슬롯 단위 advisory lock
     * 경합 (lock contention) 이 늘어난다. */
    private final Duration slotDuration;
    /** 예약 마감 — 시작 X분 전까지만 예약 가능. 검수원 준비 시간 + 셀러 이동 시간. */
    private final Duration bookingLeadTime;
    private final Instant createdAt;

    private InspectionCenter(InspectionCenterId id, String name, String address,
                             int parallelCapacity, Duration slotDuration, Duration bookingLeadTime,
                             Instant createdAt) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.parallelCapacity = parallelCapacity;
        this.slotDuration = slotDuration;
        this.bookingLeadTime = bookingLeadTime;
        this.createdAt = createdAt;
    }

    public static InspectionCenter open(String name, String address,
                                        int parallelCapacity, Duration slotDuration,
                                        Duration bookingLeadTime, Clock clock) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(address);
        Objects.requireNonNull(slotDuration);
        Objects.requireNonNull(bookingLeadTime);
        if (parallelCapacity <= 0) {
            throw new IllegalArgumentException("parallelCapacity must be positive: " + parallelCapacity);
        }
        if (slotDuration.isNegative() || slotDuration.isZero()) {
            throw new IllegalArgumentException("slotDuration must be positive");
        }
        if (bookingLeadTime.isNegative()) {
            throw new IllegalArgumentException("bookingLeadTime must be non-negative");
        }
        return new InspectionCenter(InspectionCenterId.newId(), name, address,
                parallelCapacity, slotDuration, bookingLeadTime, clock.instant());
    }

    public static InspectionCenter restore(InspectionCenterId id, String name, String address,
                                           int parallelCapacity, Duration slotDuration,
                                           Duration bookingLeadTime, Instant createdAt) {
        return new InspectionCenter(id, name, address, parallelCapacity, slotDuration,
                bookingLeadTime, createdAt);
    }

    /**
     * 주어진 시각이 어느 슬롯에 속하는지 그 슬롯의 시작 시각을 돌려준다. 슬롯은 매시간 정각부터
     * (예: 14:00, 15:00) 시작한다. UTC 기준이라 시간대(timezone) 영향 없음 — 운영 화면에서 KST
     * 등으로 변환.
     */
    public Instant slotStartFor(Instant t) {
        if (slotDuration.equals(Duration.ofHours(1))) {
            return t.truncatedTo(ChronoUnit.HOURS);
        }
        // 1시간이 아닌 경우 — 분 단위로 정렬
        long minutesSinceEpoch = t.toEpochMilli() / 60_000L;
        long slotMinutes = slotDuration.toMinutes();
        long aligned = (minutesSinceEpoch / slotMinutes) * slotMinutes;
        return Instant.ofEpochMilli(aligned * 60_000L);
    }

    public Instant slotEndFor(Instant slotStart) {
        return slotStart.plus(slotDuration);
    }

    /** 지금 시각 기준, 이 슬롯이 예약 가능 마감 시간(lead-time) 안쪽인지 — true 면 너무 임박해
     * 예약 거절 대상. */
    public boolean isWithinLeadTime(Instant slotStart, Instant now) {
        return Duration.between(now, slotStart).compareTo(bookingLeadTime) < 0;
    }

    // Getters
    public InspectionCenterId id() { return id; }
    public String name() { return name; }
    public String address() { return address; }
    public int parallelCapacity() { return parallelCapacity; }
    public Duration slotDuration() { return slotDuration; }
    public Duration bookingLeadTime() { return bookingLeadTime; }
    public Instant createdAt() { return createdAt; }
}
