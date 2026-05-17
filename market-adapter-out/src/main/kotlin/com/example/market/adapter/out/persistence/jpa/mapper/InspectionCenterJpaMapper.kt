package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.InspectionCenterJpaEntity
import com.example.market.domain.inspection.scheduling.InspectionCenter
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import java.time.Duration

object InspectionCenterJpaMapper {

    @JvmStatic
    fun toEntity(c: InspectionCenter): InspectionCenterJpaEntity =
        InspectionCenterJpaEntity(
            id = c.id.value,
            name = c.name,
            address = c.address,
            parallelCapacity = c.parallelCapacity,
            slotDurationMinutes = c.slotDuration.toMinutes(),
            bookingLeadTimeMinutes = c.bookingLeadTime.toMinutes(),
            createdAt = c.createdAt,
        )

    @JvmStatic
    fun toDomain(e: InspectionCenterJpaEntity): InspectionCenter = InspectionCenter.restore(
        InspectionCenterId(e.id!!),
        e.name!!,
        e.address!!,
        e.parallelCapacity,
        Duration.ofMinutes(e.slotDurationMinutes),
        Duration.ofMinutes(e.bookingLeadTimeMinutes),
        e.createdAt!!,
    )
}
