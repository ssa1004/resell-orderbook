package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.adapter.web.dto.AssignInspectorRequest
import com.example.market.adapter.web.dto.InspectionRequestResponse
import com.example.market.adapter.web.dto.RecordInspectionResultRequest
import com.example.market.application.command.RecordInspectionArrivalCommand
import com.example.market.application.exception.InspectionRequestNotFoundException
import com.example.market.application.port.`in`.AssignInspectorUseCase
import com.example.market.application.port.`in`.RecordInspectionArrivalUseCase
import com.example.market.application.port.`in`.RecordInspectionResultUseCase
import com.example.market.application.port.out.InspectionRequestRepository
import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.trading.TradeId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 검수센터 운영자 전용 endpoints. {@code @PreAuthorize} 로 INSPECTOR/ADMIN 권한 필요.
 */
@RestController
@RequestMapping("/api/v1/inspection-requests")
@Tag(name = "inspection", description = "검수 요청 — 운영자")
@PreAuthorize("hasAnyRole('INSPECTOR','ADMIN')")
class InspectionController(
    private val recordArrival: RecordInspectionArrivalUseCase,
    private val assignInspector: AssignInspectorUseCase,
    private val recordResult: RecordInspectionResultUseCase,
    private val inspections: InspectionRequestRepository,
    private val callerExtractor: CallerExtractor,
) {

    @PostMapping("/arrive")
    @Operation(summary = "검수센터 도착 처리 (Trade → INSPECTION_PENDING + Request open)")
    fun arrive(@RequestBody body: ArriveRequest): ResponseEntity<InspectionRequestResponse> {
        val request = recordArrival.arrive(RecordInspectionArrivalCommand(TradeId.of(body.tradeId)))
        return ResponseEntity.status(201).body(InspectionRequestResponse.from(request))
    }

    @PostMapping("/{id}/assign")
    @Operation(summary = "검수 담당자 배정")
    fun assign(
        @PathVariable id: String,
        @Valid @RequestBody req: AssignInspectorRequest,
    ): ResponseEntity<Void> {
        assignInspector.assign(req.toCommand(InspectionRequestId.of(id)))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/result")
    @Operation(summary = "검수 결과 기록 (PASS/FAIL) — Trade 분기 자동")
    fun recordResult(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
        @Valid @RequestBody req: RecordInspectionResultRequest,
    ): InspectionRequestResponse {
        val caller = callerExtractor.from(jwt)
        val request = recordResult.record(req.toCommand(caller.userId, InspectionRequestId.of(id)))
        return InspectionRequestResponse.from(request)
    }

    @GetMapping("/{id}")
    @Operation(summary = "검수 요청 조회")
    fun get(@PathVariable id: String): InspectionRequestResponse {
        val request = inspections.findById(InspectionRequestId.of(id))
            .orElseThrow { InspectionRequestNotFoundException(InspectionRequestId.of(id)) }
        return InspectionRequestResponse.from(request)
    }

    data class ArriveRequest(val tradeId: String)
}
