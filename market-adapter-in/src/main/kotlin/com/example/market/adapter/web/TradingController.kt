package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.adapter.web.dto.InstantTradeRequest
import com.example.market.adapter.web.dto.OrderBookView
import com.example.market.adapter.web.dto.PlaceBidRequest
import com.example.market.adapter.web.dto.PlaceBidResponse
import com.example.market.adapter.web.dto.PlaceListingRequest
import com.example.market.adapter.web.dto.PlaceListingResponse
import com.example.market.adapter.web.dto.TradeResponse
import com.example.market.adapter.web.dto.toBuyNowCommand
import com.example.market.adapter.web.dto.toSellNowCommand
import com.example.market.adapter.web.ratelimit.RateLimited
import com.example.market.application.command.CancelBidCommand
import com.example.market.application.command.CancelListingCommand
import com.example.market.application.port.`in`.BuyNowUseCase
import com.example.market.application.port.`in`.CancelBidUseCase
import com.example.market.application.port.`in`.CancelListingUseCase
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.application.port.`in`.PlaceBidUseCase
import com.example.market.application.port.`in`.PlaceListingUseCase
import com.example.market.application.port.`in`.SellNowUseCase
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.trading.BidId
import com.example.market.domain.trading.ListingId
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1")
@Tag(name = "trading", description = "Listing/Bid/Trade 매칭")
@Validated
class TradingController(
    private val placeListing: PlaceListingUseCase,
    private val placeBid: PlaceBidUseCase,
    private val buyNow: BuyNowUseCase,
    private val sellNow: SellNowUseCase,
    private val cancelListing: CancelListingUseCase,
    private val cancelBid: CancelBidUseCase,
    private val orderBookQuery: OrderBookQueryUseCase,
    private val callerExtractor: CallerExtractor,
) {

    // ── ASK ────────────────────────────────────

    @PostMapping("/listings")
    @Operation(summary = "판매 호가(ASK) 등록 + 즉시 매칭 시도")
    @RateLimited(capacity = 20, refillTokens = 5)
    fun place(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: PlaceListingRequest,
    ): ResponseEntity<PlaceListingResponse> {
        val caller = callerExtractor.from(jwt)
        val result = placeListing.place(req.toCommand(idempotencyKey, caller.userId()))
        val body = PlaceListingResponse(
            listingId = result.listingId().toString(),
            matchedTradeId = result.matchedTradeId().getOrNull()?.toString(),
        )
        return ResponseEntity.created(URI.create("/api/v1/listings/${result.listingId()}")).body(body)
    }

    @DeleteMapping("/listings/{id}")
    @Operation(summary = "판매 호가 취소 (본인만)")
    fun cancelListing(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        val caller = callerExtractor.from(jwt)
        cancelListing.cancel(CancelListingCommand(caller.userId(), ListingId.of(id)))
        return ResponseEntity.noContent().build()
    }

    // ── BID ────────────────────────────────────

    @PostMapping("/bids")
    @Operation(summary = "구매 호가(BID) 등록 + 즉시 매칭 시도")
    @RateLimited(capacity = 20, refillTokens = 5)
    fun placeBid(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: PlaceBidRequest,
    ): ResponseEntity<PlaceBidResponse> {
        val caller = callerExtractor.from(jwt)
        val result = placeBid.place(req.toCommand(idempotencyKey, caller.userId()))
        val body = PlaceBidResponse(
            bidId = result.bidId().toString(),
            matchedTradeId = result.matchedTradeId().getOrNull()?.toString(),
        )
        return ResponseEntity.created(URI.create("/api/v1/bids/${result.bidId()}")).body(body)
    }

    @DeleteMapping("/bids/{id}")
    @Operation(summary = "구매 호가 취소 (본인만)")
    fun cancelBid(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        val caller = callerExtractor.from(jwt)
        cancelBid.cancel(CancelBidCommand(caller.userId(), BidId.of(id)))
        return ResponseEntity.noContent().build()
    }

    // ── BuyNow / SellNow ────────────────────────────────────

    @PostMapping("/trades/buy-now")
    @Operation(summary = "즉시 구매 — Lowest ASK 매칭")
    @RateLimited(capacity = 10, refillTokens = 2)
    fun buyNow(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: InstantTradeRequest,
    ): ResponseEntity<TradeResponse> {
        val caller = callerExtractor.from(jwt)
        val trade = buyNow.buyNow(req.toBuyNowCommand(idempotencyKey, caller.userId()))
        return ResponseEntity.created(URI.create("/api/v1/trades/${trade.id()}"))
            .body(TradeResponse.from(trade))
    }

    @PostMapping("/trades/sell-now")
    @Operation(summary = "즉시 판매 — Highest BID 매칭")
    @RateLimited(capacity = 10, refillTokens = 2)
    fun sellNow(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody req: InstantTradeRequest,
    ): ResponseEntity<TradeResponse> {
        val caller = callerExtractor.from(jwt)
        val trade = sellNow.sellNow(req.toSellNowCommand(idempotencyKey, caller.userId()))
        return ResponseEntity.created(URI.create("/api/v1/trades/${trade.id()}"))
            .body(TradeResponse.from(trade))
    }

    // ── OrderBook ────────────────────────────────────

    @GetMapping("/orderbook/{skuId}")
    @Operation(summary = "호가창 조회 (Top N ASK + BID)")
    fun orderBook(
        @PathVariable skuId: String,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) depth: Int,
    ): OrderBookView {
        val view = orderBookQuery.view(SkuId.of(skuId), depth)
        return OrderBookView.from(view)
    }
}
