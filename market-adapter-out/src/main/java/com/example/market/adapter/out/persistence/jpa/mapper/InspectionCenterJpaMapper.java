package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.InspectionCenterJpaEntity;
import com.example.market.domain.inspection.scheduling.InspectionCenter;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;

import java.time.Duration;

public final class InspectionCenterJpaMapper {

    private InspectionCenterJpaMapper() {}

    public static InspectionCenterJpaEntity toEntity(InspectionCenter c) {
        return new InspectionCenterJpaEntity(
                c.id().value(),
                c.name(),
                c.address(),
                c.parallelCapacity(),
                c.slotDuration().toMinutes(),
                c.bookingLeadTime().toMinutes(),
                c.createdAt()
        );
    }

    public static InspectionCenter toDomain(InspectionCenterJpaEntity e) {
        return InspectionCenter.restore(
                new InspectionCenterId(e.getId()),
                e.getName(),
                e.getAddress(),
                e.getParallelCapacity(),
                Duration.ofMinutes(e.getSlotDurationMinutes()),
                Duration.ofMinutes(e.getBookingLeadTimeMinutes()),
                e.getCreatedAt()
        );
    }
}
