package com.example.market.application.port.in;

import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.inspection.scheduling.SlotAvailability;

import java.time.Instant;
import java.util.List;

public interface AvailableSlotsQueryUseCase {

    /**
     * {@code [from, to)} 안의 모든 슬롯의 capacity / 예약 가능 여부.
     *
     * <p>화면에서 캘린더 뷰에 "9월 5일 15:00 — 3/5 예약" 같이 표시. 모든 슬롯을 응답에 포함하므로
     * 큰 시간 구간 (예: 1달) 은 클라이언트가 페이징.</p>
     */
    List<SlotAvailability> findSlots(InspectionCenterId centerId, Instant from, Instant to);
}
