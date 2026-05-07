package com.example.market.domain.inspection.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 검수 센터 — 셀러가 매물을 가져와 검수받는 물리 장소.
 *
 * <p>한 센터는 *동시에 N개* 검수 가능 ({@code parallelCapacity} = 검수원 수). 한 시간 슬롯
 * (보통 1시간) 안에 그 이상 예약은 거절. 슬롯은 *materialized 안 함* — (centerId × slotStart)
 * 의 *암묵적* 슬롯이고, 예약 시 advisory lock + 기존 RESERVED/ARRIVED 카운트로 capacity 검사.</p>
 *
 * <p><b>왜 슬롯을 미리 만들지 않나</b>: 슬롯 = (center, time) tuple 인데, time 은 *연속*. 매일
 * batch 로 향후 30일치 슬롯을 만들면 row 폭증 + cleanup 이 또 batch. 그냥 예약이 들어올 때마다
 * 그 시각 슬롯을 *조회* 하는 게 단순. PG advisory lock 으로 동시 예약 race 막음 (ADR-0005 패턴).</p>
 *
 * <p>운영 시간 / 휴일 / 동적 capacity 변경 등은 이 모델의 후속 (별도 ADR). 현재는 *항상 개점*
 * 가정 + 정수 capacity.</p>
 */
public final class InspectionCenter {

    private final InspectionCenterId id;
    private final String name;
    private final String address;
    /** 동시 검수 가능 인원 — 한 슬롯 안 최대 RESERVED+ARRIVED 수. */
    private final int parallelCapacity;
    /** 한 슬롯 길이. 보통 60분. 짧을수록 정확하지만 예약 lock contention 증가. */
    private final Duration slotDuration;
    /** 예약 마감 — 시작 X분 전까지만 예약 가능 (검수원 준비 시간). */
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
     * 주어진 시각이 속한 슬롯의 *시작 시각*. 슬롯은 매시간 정각부터 (예: 14:00, 15:00).
     * UTC 기준 — timezone 영향 없음 (운영 화면에서 KST 변환).
     */
    public Instant slotStartFor(Instant t) {
        if (slotDuration.equals(Duration.ofHours(1))) {
            return t.truncatedTo(ChronoUnit.HOURS);
        }
        // 1시간이 아닌 경우 — 분 단위 정렬
        long minutesSinceEpoch = t.toEpochMilli() / 60_000L;
        long slotMinutes = slotDuration.toMinutes();
        long aligned = (minutesSinceEpoch / slotMinutes) * slotMinutes;
        return Instant.ofEpochMilli(aligned * 60_000L);
    }

    public Instant slotEndFor(Instant slotStart) {
        return slotStart.plus(slotDuration);
    }

    /** 지금 시각 기준, 이 슬롯이 booking lead-time 안에 있는가 (예약 가능 시간 지났는가). */
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
