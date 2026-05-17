package com.example.market.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "inspection_centers")
class InspectionCenterJpaEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "name", nullable = false, length = 128)
    var name: String? = null,

    @Column(name = "address", nullable = false, length = 512)
    var address: String? = null,

    @Column(name = "parallel_capacity", nullable = false)
    var parallelCapacity: Int = 0,

    @Column(name = "slot_duration_minutes", nullable = false)
    var slotDurationMinutes: Long = 0,

    @Column(name = "booking_lead_time_minutes", nullable = false)
    var bookingLeadTimeMinutes: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null,
)
