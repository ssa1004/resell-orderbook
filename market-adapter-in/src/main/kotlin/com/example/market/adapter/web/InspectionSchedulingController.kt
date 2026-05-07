package com.example.market.adapter.web

import com.example.market.adapter.web.dto.AppointmentView
import com.example.market.adapter.web.dto.AvailableSlotsResponse
import com.example.market.adapter.web.dto.BookAppointmentRequest
import com.example.market.adapter.web.dto.CenterListResponse
import com.example.market.adapter.web.dto.CenterView
import com.example.market.adapter.web.dto.SlotAvailabilityView
import com.example.market.application.command.BookAppointmentCommand
import com.example.market.application.port.`in`.AvailableSlotsQueryUseCase
import com.example.market.application.port.`in`.BookAppointmentUseCase
import com.example.market.application.port.`in`.InspectionAppointmentLifecycleUseCase
import com.example.market.application.port.out.InspectionCenterRepository
import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionAppointment
import com.example.market.domain.inspection.scheduling.InspectionCenter
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 검수 슬롯 예약 / 라이프사이클 API.
 *
 * <ul>
 *   <li>{@code GET /inspection/centers} — 검수 센터 목록</li>
 *   <li>{@code GET /inspection/centers/{id}/slots?from=&to=} — 캘린더 뷰</li>
 *   <li>{@code POST /inspection/appointments} — 예약</li>
 *   <li>{@code POST /inspection/appointments/{id}/cancel | arrive | complete | reject}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/inspection")
@Tag(name = "inspection-scheduling", description = "검수 센터 예약")
class InspectionSchedulingController(
    private val bookAppointment: BookAppointmentUseCase,
    private val availableSlots: AvailableSlotsQueryUseCase,
    private val lifecycle: InspectionAppointmentLifecycleUseCase,
    private val centers: InspectionCenterRepository,
) {

    @GetMapping("/centers")
    @Operation(summary = "검수 센터 목록")
    fun listCenters(): ResponseEntity<CenterListResponse> {
        val items = centers.findAll().map(::toCenterView)
        return ResponseEntity.ok(CenterListResponse(items = items))
    }

    @GetMapping("/centers/{centerId}/slots")
    @Operation(summary = "센터의 [from, to) 안 슬롯 capacity")
    fun slots(
        @PathVariable centerId: String,
        @RequestParam from: String,
        @RequestParam to: String,
    ): ResponseEntity<AvailableSlotsResponse> {
        val slots = availableSlots.findSlots(
            InspectionCenterId.of(centerId),
            Instant.parse(from),
            Instant.parse(to),
        ).map { s -> SlotAvailabilityView(
            slotStart = s.slotStart().toString(),
            slotEnd = s.slotEnd().toString(),
            totalCapacity = s.totalCapacity(),
            bookedCount = s.bookedCount(),
            remaining = s.remaining(),
            bookable = s.bookable(),
        ) }
        return ResponseEntity.ok(AvailableSlotsResponse(
            centerId = centerId, from = from, to = to, slots = slots
        ))
    }

    @PostMapping("/appointments")
    @Operation(summary = "검수 예약 — capacity 초과 시 409")
    fun book(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: BookAppointmentRequest,
    ): ResponseEntity<AppointmentView> {
        val cmd = BookAppointmentCommand(
            idempotencyKey,
            UUID.fromString(req.tradeId),
            UUID.fromString(req.centerId),
            Instant.parse(req.desiredSlotTime),
        )
        val appt = bookAppointment.book(cmd)
        return ResponseEntity.ok(toView(appt))
    }

    @PostMapping("/appointments/{id}/cancel")
    fun cancel(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.cancel(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/appointments/{id}/arrive")
    fun arrive(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.markArrived(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/appointments/{id}/complete")
    fun complete(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.markCompleted(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/appointments/{id}/reject")
    fun reject(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.markRejected(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    private fun toView(a: InspectionAppointment): AppointmentView = AppointmentView(
        id = a.id().toString(),
        tradeId = a.tradeId().toString(),
        centerId = a.centerId().toString(),
        slotStart = a.slotStart().toString(),
        slotEnd = a.slotEnd().toString(),
        status = a.status().name,
        bookedAt = a.bookedAt().toString(),
    )

    private fun toCenterView(c: InspectionCenter): CenterView = CenterView(
        id = c.id().toString(),
        name = c.name(),
        address = c.address(),
        parallelCapacity = c.parallelCapacity(),
        slotDurationMinutes = c.slotDuration().toMinutes(),
        bookingLeadTimeMinutes = c.bookingLeadTime().toMinutes(),
    )
}
