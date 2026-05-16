package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.adapter.web.dto.CursorPageResponse
import com.example.market.adapter.web.dto.RecordSellerShippingRequest
import com.example.market.adapter.web.dto.TradeResponse
import com.example.market.application.command.CompleteTradeCommand
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.exception.UnauthorizedTradeOperationException
import com.example.market.application.pagination.Cursor
import com.example.market.application.port.`in`.CompleteTradeUseCase
import com.example.market.application.port.`in`.RecordSellerShippingUseCase
import com.example.market.application.port.`in`.TradeHistoryQueryUseCase
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.TradeId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kotlin.jvm.optionals.getOrNull
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Trade 라이프사이클 — 판매자 발송 + 구매자 수령 + 거래 조회.
 * 검수/정산/환불은 별도 Controller (운영자 전용 또는 자동 컨슈머).
 */
@RestController
@RequestMapping("/api/v1/trades")
@Tag(name = "trade-lifecycle", description = "거래 라이프사이클 — 발송/수령/조회")
@Validated
class TradeLifecycleController(
    private val recordSellerShipping: RecordSellerShippingUseCase,
    private val completeTrade: CompleteTradeUseCase,
    private val tradeHistory: TradeHistoryQueryUseCase,
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
        recordSellerShipping.recordShipping(req.toCommand(caller.userId, TradeId.of(id)))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "구매자 수령 완료 → COMPLETED")
    fun complete(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): TradeResponse {
        val caller = callerExtractor.from(jwt)
        val trade = completeTrade.complete(CompleteTradeCommand(caller.userId, TradeId.of(id)))
        return TradeResponse.from(trade)
    }

    /**
     * 거래 단건 조회 — 본인 (buyer 또는 seller) 만 허용.
     *
     * <p>BOLA (Broken Object Level Authorization) 차단. 거래 ID 는 UUIDv4 라 추측은 어렵지만,
     * 거래 ID 가 우연히 노출되더라도 (로그/스크린샷/공유) 제3자가 buyerId/sellerId/가격을 조회
     * 하지 못하게 한다. 검수/정산 운영자가 봐야 할 때는 별도 ADMIN endpoint 로 분리.</p>
     */
    @GetMapping("/{id}")
    @Operation(summary = "거래 조회 (본인 = buyer 또는 seller 만)")
    fun get(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): TradeResponse {
        val caller = callerExtractor.from(jwt)
        val trade = trades.findById(TradeId.of(id))
            .orElseThrow { TradeNotFoundException(TradeId.of(id)) }
        val callerId = caller.userId
        if (callerId != trade.buyerId && callerId != trade.sellerId) {
            throw UnauthorizedTradeOperationException(trade.id, callerId, "read")
        }
        return TradeResponse.from(trade)
    }

    /**
     * 호출자 본인의 거래 내역 — *최신 → 과거* 순서, cursor pagination (ADR-0025).
     *
     * - `cursor` 미전달 = 첫 페이지부터
     * - 응답의 `nextCursor` 가 null 이면 마지막 페이지
     * - 다음 페이지: 받은 `nextCursor` 를 그대로 다음 요청에 포함
     */
    @GetMapping("/me/history")
    @Operation(summary = "내 거래 내역 (cursor pagination)")
    fun history(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ): CursorPageResponse<TradeResponse> {
        val caller = callerExtractor.from(jwt)
        val page = tradeHistory.historyOf(caller.userId, Cursor.of(cursor ?: ""), limit)
        return CursorPageResponse(
            items = page.items.map { TradeResponse.from(it) },
            nextCursor = page.nextCursor().getOrNull()?.token,
        )
    }
}
