package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.adapter.web.dto.RecordSellerShippingRequest
import com.example.market.adapter.web.dto.TradeResponse
import com.example.market.application.command.CompleteTradeCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.port.`in`.CompleteTradeUseCase
import com.example.market.application.port.`in`.RecordSellerShippingUseCase
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.TradeId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Trade 라이프사이클 — 판매자 발송 + 구매자 수령 + 거래 조회.
 * 검수/정산/환불은 별도 Controller (운영자 전용 또는 자동 컨슈머).
 */
@RestController
@RequestMapping("/api/v1/trades")
@Tag(name = "trade-lifecycle", description = "거래 라이프사이클 — 발송/수령/조회")
class TradeLifecycleController(
    private val recordSellerShipping: RecordSellerShippingUseCase,
    private val completeTrade: CompleteTradeUseCase,
    private val trades: TradeRepository,
    private val callerExtractor: CallerExtractor,
) {

    @PostMapping("/{id}/seller-shipping")
    @Operation(summary = "판매자 발송 (송장 입력)")
    fun shipping(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
        @Valid @RequestBody req: RecordSellerShippingRequest,
    ): ResponseEntity<Void> {
        val caller = callerExtractor.from(jwt)
        recordSellerShipping.recordShipping(req.toCommand(caller.userId(), TradeId.of(id)))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "구매자 수령 완료 → COMPLETED")
    fun complete(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): TradeResponse {
        val caller = callerExtractor.from(jwt)
        val trade = completeTrade.complete(CompleteTradeCommand(caller.userId(), TradeId.of(id)))
        return TradeResponse.from(trade)
    }

    @GetMapping("/{id}")
    @Operation(summary = "거래 조회")
    fun get(@PathVariable id: String): TradeResponse {
        val trade = trades.findById(TradeId.of(id))
            .orElseThrow { TradeNotFoundException(TradeId.of(id)) }
        return TradeResponse.from(trade)
    }
}
