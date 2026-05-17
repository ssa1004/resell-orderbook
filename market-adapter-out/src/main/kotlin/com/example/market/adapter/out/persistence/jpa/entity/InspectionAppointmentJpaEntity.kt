package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.inspection.scheduling.AppointmentStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inspection_appointments")
class InspectionAppointmentJpaEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "trade_id", nullable = false)
    var tradeId: UUID? = null,

    @Column(name = "center_id", nullable = false)
    var centerId: UUID? = null,

    @Column(name = "slot_start", nullable = false)
    var slotStart: Instant? = null,

    @Column(name = "slot_end", nullable = false)
    var slotEnd: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AppointmentStatus? = null,

    @Column(name = "booked_at", nullable = false)
    var bookedAt: Instant? = null,

    @Column(name = "arrived_at")
    var arrivedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)
