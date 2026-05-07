package com.example.market.adapter.web.dto

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
)

data class CenterView(
    val id: String,
    val name: String,
    val address: String,
    val parallelCapacity: Int,
    val slotDurationMinutes: Long,
    val bookingLeadTimeMinutes: Long,
)

data class CenterListResponse(val items: List<CenterView>)

data class SlotAvailabilityView(
    val slotStart: String,
    val slotEnd: String,
    val totalCapacity: Int,
    val bookedCount: Int,
    val remaining: Int,
    val bookable: Boolean,
)

data class AvailableSlotsResponse(
    val centerId: String,
    val from: String,
    val to: String,
    val slots: List<SlotAvailabilityView>,
)
