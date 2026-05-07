package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.InspectionAppointmentJpaEntity;
import com.example.market.domain.inspection.scheduling.AppointmentId;
import com.example.market.domain.inspection.scheduling.InspectionAppointment;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import com.example.market.domain.trading.TradeId;

public final class InspectionAppointmentJpaMapper {

    private InspectionAppointmentJpaMapper() {}

    public static InspectionAppointmentJpaEntity toEntity(InspectionAppointment a) {
        return new InspectionAppointmentJpaEntity(
                a.id().value(),
                a.tradeId().value(),
                a.centerId().value(),
                a.slotStart(),
                a.slotEnd(),
                a.status(),
                a.bookedAt(),
                a.arrivedAt(),
                a.completedAt(),
                a.version()
        );
    }

    public static InspectionAppointment toDomain(InspectionAppointmentJpaEntity e) {
        return InspectionAppointment.restore(
                new AppointmentId(e.getId()),
                TradeId.of(e.getTradeId().toString()),
                new InspectionCenterId(e.getCenterId()),
                e.getSlotStart(),
                e.getSlotEnd(),
                e.getStatus(),
                e.getBookedAt(),
                e.getArrivedAt(),
                e.getCompletedAt(),
                e.getVersion()
        );
    }
}
