package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.InspectionAppointmentJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataInspectionAppointmentRepository
import com.example.market.application.port.out.InspectionAppointmentRepository
import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.trading.TradeId
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
class JpaInspectionAppointmentRepositoryAdapter(
    private val jpa: SpringDataInspectionAppointmentRepository,
) : InspectionAppointmentRepository {

    override fun save(appointment: InspectionAppointment) {
        jpa.save(InspectionAppointmentJpaMapper.toEntity(appointment))
    }

    override fun findById(id: AppointmentId): Optional<InspectionAppointment> =
        jpa.findById(id.value).map(InspectionAppointmentJpaMapper::toDomain)

    override fun countActive(centerId: InspectionCenterId, slotStart: Instant): Long =
        jpa.countActiveBookings(centerId.value, slotStart)

    override fun countActiveInRange(
        centerId: InspectionCenterId,
        from: Instant,
        to: Instant,
    ): Map<Instant, Long> {
        val result = LinkedHashMap<Instant, Long>()
        for (row in jpa.countActiveBookingsInRange(centerId.value, from, to)) {
            val slotStart = row[0] as Instant
            val count = row[1] as Long
            result[slotStart] = count
        }
        return result
    }

    override fun findActiveByTrade(tradeId: TradeId): List<InspectionAppointment> =
        jpa.findActiveByTrade(tradeId.value).map(InspectionAppointmentJpaMapper::toDomain)
}
