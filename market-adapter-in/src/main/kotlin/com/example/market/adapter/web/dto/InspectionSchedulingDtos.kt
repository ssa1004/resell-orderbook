package com.example.market.adapter.web.dto

import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.inspection.scheduling.InspectionCenter
import com.example.market.domain.inspection.scheduling.SlotAvailability
import jakarta.validation.constraints.NotBlank

data class BookAppointmentRequest(
    @field:NotBlank val tradeId: String,
    @field:NotBlank val centerId: String,
    /** ISO-8601. 도메인이 이 시각이 속한 슬롯 시작으로 정렬. */
    @field:NotBlank val desiredSlotTime: String,
)

data class AppointmentView(
    val id: String,
    val tradeId: String,
    val centerId: String,
    val slotStart: String,
    val slotEnd: String,
    val status: String,
    val bookedAt: String,
) {
    companion object {
        fun from(a: InspectionAppointment): AppointmentView = AppointmentView(
            id = a.id().toString(),
            tradeId = a.tradeId().toString(),
            centerId = a.centerId().toString(),
            slotStart = a.slotStart().toString(),
            slotEnd = a.slotEnd().toString(),
            status = a.status().name,
            bookedAt = a.bookedAt().toString(),
        )
    }
}

data class CenterView(
    val id: String,
    val name: String,
    val address: String,
    val parallelCapacity: Int,
    val slotDurationMinutes: Long,
    val bookingLeadTimeMinutes: Long,
) {
    companion object {
        fun from(c: InspectionCenter): CenterView = CenterView(
            id = c.id().toString(),
            name = c.name(),
            address = c.address(),
            parallelCapacity = c.parallelCapacity(),
            slotDurationMinutes = c.slotDuration().toMinutes(),
            bookingLeadTimeMinutes = c.bookingLeadTime().toMinutes(),
        )
    }
}

data class CenterListResponse(val items: List<CenterView>)

data class SlotAvailabilityView(
    val slotStart: String,
    val slotEnd: String,
    val totalCapacity: Int,
    val bookedCount: Int,
    val remaining: Int,
    val bookable: Boolean,
) {
    companion object {
        fun from(s: SlotAvailability): SlotAvailabilityView = SlotAvailabilityView(
            slotStart = s.slotStart().toString(),
            slotEnd = s.slotEnd().toString(),
            totalCapacity = s.totalCapacity(),
            bookedCount = s.bookedCount(),
            remaining = s.remaining(),
            bookable = s.bookable(),
        )
    }
}

data class AvailableSlotsResponse(
    val centerId: String,
    val from: String,
    val to: String,
    val slots: List<SlotAvailabilityView>,
)
