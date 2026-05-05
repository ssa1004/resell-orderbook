package com.example.market.adapter.web

import com.example.market.application.port.`in`.RetryRefundUseCase
import com.example.market.domain.settlement.RefundId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 admin endpoints — Refund.FAILED 재시도 등.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin", description = "운영자 전용")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(
    private val retryRefund: RetryRefundUseCase,
) {

    @PostMapping("/refunds/{id}/retry")
    @Operation(summary = "Refund.FAILED → PG.refund 재시도")
    fun retry(@PathVariable id: String): ResponseEntity<Void> {
        retryRefund.retry(RefundId.of(id))
        return ResponseEntity.accepted().build()
    }
}
