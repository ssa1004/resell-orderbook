package com.example.market.application.service

import com.example.market.application.pagination.Cursor
import com.example.market.application.pagination.CursorCodec
import com.example.market.application.pagination.CursorPage
import com.example.market.application.port.`in`.MarketDataQueryUseCase
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.application.port.out.MarketStatsCache
import com.example.market.application.port.out.OhlcCandleRepository
import com.example.market.application.port.out.PriceTickRepository
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.MarketStats
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.marketdata.OhlcPeriod
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.Money
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 시세 read service. write side (매칭 / 호가 등록) 와 격리.
 *
 * [currentStats] 는 한 SKU 의 *현재 화면 카드* 를 만든다 — 호가창 best bid/ask
 * 와 가장 최근 체결가 + 24h 집계를 한 번의 응답으로 묶어 클라이언트가 추가 round-trip 없이
 * 받을 수 있게.
 *
 * hot SKU 의 반복 조회는 [MarketStatsCache] (Caffeine L1 + Redis L2, ADR-0019) 가
 * 흡수 — 매번 DB 의 24h aggregation (COUNT/MIN/AVG/MAX) 을 돌리지 않는다. cache miss 시에만
 * loader 가 실제 DB 쿼리를 수행하고, 그 결과는 1초 (L1) / 10초 (L2) TTL 로 보관. cache stampede
 * 는 probabilistic early refresh + SETNX lock 으로 차단.
 */
@Service
@Transactional(readOnly = true)
open class MarketDataQueryService(
    private val ticks: PriceTickRepository,
    private val candles: OhlcCandleRepository,
    private val orderBookQuery: OrderBookQueryUseCase,
    private val statsCache: MarketStatsCache,
    private val clock: Clock,
) : MarketDataQueryUseCase {

    override fun currentStats(skuId: SkuId): MarketStats {
        // cache miss 또는 stale 임박 시에만 computeStats() 가 호출됨. cache hit 은 ns~ms 단위.
        return statsCache.getOrCompute(skuId, Supplier { computeStats(skuId) })
    }

    private fun computeStats(skuId: SkuId): MarketStats {
        val now = clock.instant()
        val from24h = now.minus(WINDOW_24H)

        val last = ticks.findLatest(skuId)
        val orderBook = orderBookQuery.view(skuId, 1)
        val agg = ticks.aggregate(skuId, from24h, now)

        val bestBid: Money? = orderBook.highestBid.orElse(null)
        val bestAsk: Money? = orderBook.lowestAsk.orElse(null)
        val spread: Money? = if (bestBid != null && bestAsk != null) bestAsk.subtract(bestBid) else null

        return MarketStats(
            skuId,
            now,
            last.map { it.price }.orElse(null),
            last.map { it.occurredAt }.orElse(null),
            bestBid,
            bestAsk,
            spread,
            agg.count,
            agg.min,
            agg.avg,
            agg.max,
        )
    }

    override fun ticks(skuId: SkuId, from: Instant, to: Instant, limit: Int): List<PriceTick> {
        return ticks.findBySkuInRange(skuId, from, to, limit)
    }

    override fun ohlc(skuId: SkuId, period: OhlcPeriod, from: Instant, to: Instant, limit: Int): List<OhlcCandle> {
        return candles.findBySkuInRange(skuId, period, from, to, limit)
    }

    /**
     * 체결 틱 cursor pagination — Snowflake long ID 가 시간 단조 증가라 *단일 long cursor* 만으로
     * 결정성 보장 (Trade history 와 달리 UUID tie-breaker 불필요).
     *
     * N+1 패턴 — limit + 1 row 를 조회해 다음 페이지 존재 판정.
     */
    override fun ticksAfter(skuId: SkuId, after: Cursor, limit: Int): CursorPage<PriceTick> {
        val safeLimit = limit.coerceIn(1, 1000)
        val afterId: Long = if (after.isEmpty()) 0L else CursorCodec.decodeLong(after)

        val raw = ticks.findBySkuAfter(skuId, afterId, safeLimit + 1)

        if (raw.size <= safeLimit) {
            return CursorPage.last(raw)
        }
        val page = raw.subList(0, safeLimit)
        val boundaryId = page[safeLimit - 1].id
        return CursorPage.of(page, CursorCodec.encodeLong(boundaryId))
    }

    companion object {
        private val WINDOW_24H: Duration = Duration.ofHours(24)
    }
}
