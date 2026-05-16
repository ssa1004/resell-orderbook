package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
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
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
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
 *   <li>{@code POST /inspection/appointments/{id}/cancel} — 셀러 본인 취소</li>
 *   <li>{@code POST /inspection/appointments/{id}/arrive | complete | reject} — 운영자 전용</li>
 * </ul>
 *
 * <p>권한: {@code arrive / complete / reject} 는 {@code INSPECTOR / ADMIN} 역할 필요
 * (운영자 전용). {@code cancel} 은 거래(Trade) 의 셀러 본인 — service 단에서 caller-vs-owner
 * 검사. 단순 조회는 인증만 통과하면 OK.</p>
 */
@RestController
@RequestMapping("/api/v1/inspection")
@Tag(name = "inspection-scheduling", description = "검수 센터 예약")
@Validated
class InspectionSchedulingController(
    private val bookAppointment: BookAppointmentUseCase,
    private val availableSlots: AvailableSlotsQueryUseCase,
    private val lifecycle: InspectionAppointmentLifecycleUseCase,
    private val centers: InspectionCenterRepository,
    private val callerExtractor: CallerExtractor,
) {

    @GetMapping("/centers")
    @Operation(summary = "검수 센터 목록")
    fun listCenters(): ResponseEntity<CenterListResponse> {
        val items = centers.findAll().map(CenterView::from)
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
        ).map(SlotAvailabilityView::from)
        return ResponseEntity.ok(AvailableSlotsResponse(
            centerId = centerId, from = from, to = to, slots = slots
        ))
    }

    @PostMapping("/appointments")
    @Operation(summary = "검수 예약 — capacity 초과 시 409")
    fun book(
        @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 1, max = 128) idempotencyKey: String,
        @Valid @RequestBody req: BookAppointmentRequest,
    ): ResponseEntity<AppointmentView> {
        val cmd = BookAppointmentCommand(
            idempotencyKey,
            UUID.fromString(req.tradeId),
            UUID.fromString(req.centerId),
            Instant.parse(req.desiredSlotTime),
        )
        val appt = bookAppointment.book(cmd)
        return ResponseEntity.ok(AppointmentView.from(appt))
    }

    @PostMapping("/appointments/{id}/cancel")
    @Operation(summary = "셀러 본인 취소 — 다른 사용자 호출 시 403")
    fun cancel(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        val caller = callerExtractor.from(jwt)
        lifecycle.cancel(caller.userId, AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/appointments/{id}/arrive")
    @PreAuthorize("hasAnyRole('INSPECTOR','ADMIN')")
    fun arrive(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.markArrived(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/appointments/{id}/complete")
    @PreAuthorize("hasAnyRole('INSPECTOR','ADMIN')")
    fun complete(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.markCompleted(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/appointments/{id}/reject")
    @PreAuthorize("hasAnyRole('INSPECTOR','ADMIN')")
    fun reject(@PathVariable id: String): ResponseEntity<Void> {
        lifecycle.markRejected(AppointmentId.of(id))
        return ResponseEntity.noContent().build()
    }
}
