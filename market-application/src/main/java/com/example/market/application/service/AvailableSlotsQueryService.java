package com.example.market.application.service;

import com.example.market.application.exception.InspectionExceptions;
import com.example.market.application.port.in.AvailableSlotsQueryUseCase;
import com.example.market.application.port.out.InspectionAppointmentRepository;
import com.example.market.application.port.out.InspectionCenterRepository;
import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.inspection.scheduling.SlotAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 캘린더 뷰 — {@code [from, to)} 안의 모든 슬롯의 capacity / 예약 가능 여부.
 *
 * <p>활성 카운트는 1개의 GROUP BY query 로 (모든 슬롯이 0인 경우엔 응답에서 0 으로 채움).
 * 슬롯이 많아도 query 1번이라 빠름.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AvailableSlotsQueryService implements AvailableSlotsQueryUseCase {

    private final InspectionCenterRepository centers;
    private final InspectionAppointmentRepository appointments;
    private final Clock clock;

    @Override
    public List<SlotAvailability> findSlots(InspectionCenterId centerId, Instant from, Instant to) {
        InspectionCenter center = centers.findById(centerId)
                .orElseThrow(() -> new InspectionExceptions.CenterNotFoundException(centerId));
        Instant now = clock.instant();
        Map<Instant, Long> counts = appointments.countActiveInRange(centerId, from, to);

        List<SlotAvailability> result = new ArrayList<>();
        // 슬롯들을 from 부터 to 직전까지 슬롯 단위로 walk
        Instant cursor = center.slotStartFor(from);
        if (cursor.isBefore(from)) {
            cursor = cursor.plus(center.slotDuration());
        }
        while (cursor.isBefore(to)) {
            long booked = counts.getOrDefault(cursor, 0L);
            boolean bookable = booked < center.parallelCapacity()
                    && !center.isWithinLeadTime(cursor, now);
            result.add(new SlotAvailability(
                    centerId,
                    cursor,
                    center.slotEndFor(cursor),
                    center.parallelCapacity(),
                    (int) booked,
                    bookable
            ));
            cursor = cursor.plus(center.slotDuration());
        }
        return result;
    }
}
