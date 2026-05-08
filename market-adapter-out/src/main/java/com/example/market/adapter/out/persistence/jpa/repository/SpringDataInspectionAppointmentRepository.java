package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.InspectionAppointmentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataInspectionAppointmentRepository
        extends JpaRepository<InspectionAppointmentJpaEntity, UUID> {

    /**
     * 한 (center × slot) 의 *capacity 점유 중* (RESERVED + ARRIVED) 카운트.
     * BookAppointmentService 가 advisory lock 잡고 이 query 로 capacity 체크.
     */
    @Query("""
            SELECT COUNT(a) FROM InspectionAppointmentJpaEntity a
             WHERE a.centerId = :centerId
               AND a.slotStart = :slotStart
               AND a.status IN (com.example.market.domain.inspection.scheduling.AppointmentStatus.RESERVED,
                                 com.example.market.domain.inspection.scheduling.AppointmentStatus.ARRIVED)
            """)
    long countActiveBookings(@Param("centerId") UUID centerId,
                             @Param("slotStart") Instant slotStart);

    /**
     * 한 시간 구간의 모든 슬롯의 활성 카운트 — Available slots query 가 사용.
     * (centerId, slotStart) 그룹별 카운트.
     */
    @Query("""
            SELECT a.slotStart, COUNT(a) FROM InspectionAppointmentJpaEntity a
             WHERE a.centerId = :centerId
               AND a.slotStart >= :from
               AND a.slotStart <  :to
               AND a.status IN (com.example.market.domain.inspection.scheduling.AppointmentStatus.RESERVED,
                                 com.example.market.domain.inspection.scheduling.AppointmentStatus.ARRIVED)
             GROUP BY a.slotStart
            """)
    List<Object[]> countActiveBookingsInRange(@Param("centerId") UUID centerId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** 한 trade 의 active 예약 — 한 trade 당 1개만 허용 (중복 예약 방지). */
    @Query("""
            SELECT a FROM InspectionAppointmentJpaEntity a
             WHERE a.tradeId = :tradeId
               AND a.status IN (com.example.market.domain.inspection.scheduling.AppointmentStatus.RESERVED,
                                 com.example.market.domain.inspection.scheduling.AppointmentStatus.ARRIVED)
            """)
    List<InspectionAppointmentJpaEntity> findActiveByTrade(@Param("tradeId") UUID tradeId);
}
