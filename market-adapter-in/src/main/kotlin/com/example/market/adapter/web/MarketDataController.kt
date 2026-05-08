package com.example.market.adapter.web

import com.example.market.adapter.web.dto.CursorPageResponse
import com.example.market.adapter.web.dto.MarketStatsResponse
import com.example.market.adapter.web.dto.OhlcCandleView
import com.example.market.adapter.web.dto.OhlcCandlesResponse
import com.example.market.adapter.web.dto.PriceTickView
import com.example.market.adapter.web.dto.PriceTicksResponse
import com.example.market.application.pagination.Cursor
import com.example.market.application.port.`in`.MarketDataQueryUseCase
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.OhlcPeriod
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
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
@Validated
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
        @RequestParam(defaultValue = "1000") @Min(1) @Max(10_000) limit: Int,
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

    /**
     * 체결 틱 — cursor pagination (ADR-0025). 실시간 차트 follow-up 용.
     *
     * - `cursor` 미전달 = 가장 오래된 틱부터 (또는 SKU 의 가장 처음 틱)
     * - 응답의 `nextCursor` 를 그대로 다음 요청에 포함하면 *그 다음 묶음* 만 받아옴
     * - WebSocket push 에 비해 *클라이언트 시작* 으로 폴링이 가능. 차트 백필 (back-fill) 에도 적합.
     */
    @GetMapping("/ticks/{skuId}/cursor")
    @Operation(summary = "체결 틱 cursor pagination — 무한 스크롤 / 차트 백필")
    fun ticksCursor(
        @PathVariable skuId: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "200") @Min(1) @Max(1000) limit: Int,
    ): ResponseEntity<CursorPageResponse<PriceTickView>> {
        val page = marketData.ticksAfter(SkuId.of(skuId), Cursor.of(cursor ?: ""), limit)
        return ResponseEntity.ok(
            CursorPageResponse(
                items = page.items().map {
                    PriceTickView(
                        tradeId = it.tradeId().toString(),
                        price = it.price().amount(),
                        occurredAt = it.occurredAt().toString(),
                    )
                },
                nextCursor = page.nextCursor().map { it.token() }.orElse(null),
            )
        )
    }

    @GetMapping("/ohlc/{skuId}")
    @Operation(summary = "OHLC 캔들스틱 — TradingView/Highcharts 표준 형태")
    fun ohlc(
        @PathVariable skuId: String,
        @RequestParam period: String,       // ONE_MIN / FIVE_MIN / ONE_HOUR / ONE_DAY
        @RequestParam from: String,
        @RequestParam to: String,
        @RequestParam(defaultValue = "1000") @Min(1) @Max(10_000) limit: Int,
    ): ResponseEntity<OhlcCandlesResponse> {
        val periodEnum = OhlcPeriod.valueOf(period)
        val fromInstant = Instant.parse(from)
        val toInstant = Instant.parse(to)
        val candles = marketData.ohlc(SkuId.of(skuId), periodEnum, fromInstant, toInstant, limit)
        val currency = candles.firstOrNull()?.open()?.currency()?.currencyCode ?: "KRW"
        return ResponseEntity.ok(OhlcCandlesResponse(
            skuId = skuId,
            period = periodEnum.name,
            from = from,
            to = to,
            currency = currency,
            candles = candles.map { c -> OhlcCandleView(
                bucketStart = c.bucketStart().toString(),
                open = c.open().amount(),
                high = c.high().amount(),
                low = c.low().amount(),
                close = c.close().amount(),
                volume = c.volume(),
                tradeCount = c.tradeCount(),
            ) },
        ))
    }
}
