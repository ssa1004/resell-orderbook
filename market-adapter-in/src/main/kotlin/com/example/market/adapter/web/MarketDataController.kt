package com.example.market.adapter.web

import com.example.market.adapter.web.dto.MarketStatsResponse
import com.example.market.adapter.web.dto.PriceTickView
import com.example.market.adapter.web.dto.PriceTicksResponse
import com.example.market.application.port.`in`.MarketDataQueryUseCase
import com.example.market.domain.catalog.SkuId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 시세 / 차트 조회 API.
 *
 * <p>주식 거래소의 시세 카드 + 가격 차트 데이터에 해당. 모두 read-only.
 * 트래픽이 늘면 응답 캐싱 / read replica 분리 모두 자연스러움.</p>
 */
@RestController
@RequestMapping("/api/v1/market")
@Tag(name = "market-data", description = "시세 카드 / 가격 차트 raw 데이터")
class MarketDataController(
    private val marketData: MarketDataQueryUseCase,
) {

    @GetMapping("/stats/{skuId}")
    @Operation(summary = "SKU 의 현재 시세 카드 (last trade + best bid/ask + 24h 통계)")
    fun stats(@PathVariable skuId: String): ResponseEntity<MarketStatsResponse> {
        val s = marketData.currentStats(SkuId.of(skuId))
        return ResponseEntity.ok(
            MarketStatsResponse(
                skuId = s.skuId().value().toString(),
                asOf = s.asOf().toString(),
                // 통화는 가격 필드에서 — 없으면 KRW default
                currency = (s.lastTradePrice() ?: s.bestBid() ?: s.bestAsk())
                    ?.currency()?.currencyCode ?: "KRW",
                lastTradePrice = s.lastTradePrice()?.amount(),
                lastTradeAt = s.lastTradeAt()?.toString(),
                bestBid = s.bestBid()?.amount(),
                bestAsk = s.bestAsk()?.amount(),
                spread = s.spread()?.amount(),
                last24hVolume = s.last24hVolume(),
                last24hMin = s.last24hMin()?.amount(),
                last24hAvg = s.last24hAvg()?.amount(),
                last24hMax = s.last24hMax()?.amount(),
            )
        )
    }

    @GetMapping("/ticks/{skuId}")
    @Operation(summary = "차트 raw 데이터 — [from, to) 의 체결 틱들")
    fun ticks(
        @PathVariable skuId: String,
        @RequestParam from: String,         // ISO-8601
        @RequestParam to: String,           // ISO-8601
        @RequestParam(defaultValue = "1000") limit: Int,
    ): ResponseEntity<PriceTicksResponse> {
        val fromInstant = Instant.parse(from)
        val toInstant = Instant.parse(to)
        val ticks = marketData.ticks(SkuId.of(skuId), fromInstant, toInstant, limit)
            .map { PriceTickView(
                tradeId = it.tradeId().toString(),
                price = it.price().amount(),
                occurredAt = it.occurredAt().toString(),
            ) }
        return ResponseEntity.ok(PriceTicksResponse(
            skuId = skuId,
            from = from,
            to = to,
            ticks = ticks,
        ))
    }
}
