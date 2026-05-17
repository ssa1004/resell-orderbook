package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.InspectionAppointmentJpaEntity
import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.trading.TradeId

object InspectionAppointmentJpaMapper {

    @JvmStatic
    fun toEntity(a: InspectionAppointment): InspectionAppointmentJpaEntity =
        InspectionAppointmentJpaEntity(
            id = a.id.value,
            tradeId = a.tradeId.value,
            centerId = a.centerId.value,
            slotStart = a.slotStart,
            slotEnd = a.slotEnd,
            status = a.status,
            bookedAt = a.bookedAt,
            arrivedAt = a.arrivedAt,
            completedAt = a.completedAt,
            version = a.version,
        )

    @JvmStatic
    fun toDomain(e: InspectionAppointmentJpaEntity): InspectionAppointment =
        InspectionAppointment.restore(
            AppointmentId(e.id!!),
            TradeId(e.tradeId!!),
            InspectionCenterId(e.centerId!!),
            e.slotStart!!,
            e.slotEnd!!,
            e.status!!,
            e.bookedAt!!,
            e.arrivedAt,
            e.completedAt,
            e.version,
        )
}
