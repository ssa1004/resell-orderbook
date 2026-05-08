package com.example.market.application.port.out;

import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.trading.TradeId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface InspectionAppointmentRepository {

    void save(InspectionAppointment appointment);

    Optional<InspectionAppointment> findById(AppointmentId id);

    /**
     * 한 (center × slot) 의 *capacity 점유 중* 예약 수. BookAppointmentService 가 advisory
     * lock 잡고 이 값으로 capacity 검사 → INSERT 결정.
     */
    long countActive(InspectionCenterId centerId, Instant slotStart);

    /** 한 시간 구간 내 슬롯별 카운트. AvailableSlotsQuery 가 사용. */
    Map<Instant, Long> countActiveInRange(InspectionCenterId centerId, Instant from, Instant to);

    /** 한 trade 의 active 예약 — 중복 예약 방지. */
    List<InspectionAppointment> findActiveByTrade(TradeId tradeId);
}
