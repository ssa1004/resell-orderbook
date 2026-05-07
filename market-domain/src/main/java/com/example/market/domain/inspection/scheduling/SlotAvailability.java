package com.example.market.domain.inspection.scheduling;

import java.time.Instant;
import java.util.Objects;

/**
 * 한 슬롯의 예약 가능 여부 + 잔여 capacity (read model).
 *
 * <p>화면에서 "9월 5일 15:00 슬롯에 X명 예약 가능" 같은 표시. 운영자도 이걸로 capacity
 * 사용률 모니터링.</p>
 */
public record SlotAvailability(
        InspectionCenterId centerId,
        Instant slotStart,
        Instant slotEnd,
        int totalCapacity,
        int bookedCount,
        boolean bookable        // capacity 남아 있고 + booking lead-time 안에 있지 않음
) {

    public SlotAvailability {
        Objects.requireNonNull(centerId);
        Objects.requireNonNull(slotStart);
        Objects.requireNonNull(slotEnd);
        if (totalCapacity < 0 || bookedCount < 0) {
            throw new IllegalArgumentException("capacity / booked must be non-negative");
        }
        if (bookedCount > totalCapacity) {
            throw new IllegalArgumentException(
                    "bookedCount (%d) > totalCapacity (%d) — over-booking detected".formatted(
                            bookedCount, totalCapacity));
        }
    }

    public int remaining() {
        return totalCapacity - bookedCount;
    }
}
