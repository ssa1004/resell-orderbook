package com.example.market.application.port.`in`

import com.example.market.application.pagination.Cursor
import com.example.market.application.pagination.CursorPage
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.MarketStats
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.marketdata.OhlcPeriod
import com.example.market.domain.marketdata.PriceTick
import java.time.Instant

/**
 * 시세 read API.
 *
 * 주식 시장의 *시세 표시 화면 + 차트 데이터* 와 같은 역할. 모두 read-only.
 */
interface MarketDataQueryUseCase {

    /** 한 SKU 의 현재 시세 카드 — last trade + best bid/ask + 24h 통계. */
    fun currentStats(skuId: SkuId): MarketStats

    /**
     * 차트 / chart 데이터 — `[from, to)` 안의 체결 틱들. 시간 역순.
     * limit 으로 최대 개수 제어 (예: 차트 1000점).
     */
    fun ticks(skuId: SkuId, from: Instant, to: Instant, limit: Int): List<PriceTick>

    /**
     * OHLC 캔들스틱 차트 데이터 — 사전 집계된 candle 들. 시간 역순.
     *
     * raw [ticks] 보다 훨씬 가벼움 (1일 ONE_MIN 차트면 1440개 vs raw 수만 tick).
     * Frontend 차트 라이브러리 (TradingView / Highcharts) 의 표준 입력 형태.
     */
    fun ohlc(skuId: SkuId, period: OhlcPeriod, from: Instant, to: Instant, limit: Int): List<OhlcCandle>

    /**
     * 체결 틱을 cursor 기반으로 *과거 → 미래* 순회 (ADR-0025).
     *
     * 실시간 차트 follow-up 용 — 클라이언트가 현재 시점 이후 새로 발생한 틱만 받아오는 패턴.
     * Snowflake long ID (ADR-0018) 가 시간 단조 증가라 단일 long cursor 로 충분 (UUID tie-breaker
     * 불필요).
     *
     * @param after Snowflake id cursor (null/empty = 첫 페이지)
     * @param limit 한 페이지 크기 (1 ~ 1000)
     */
    fun ticksAfter(skuId: SkuId, after: Cursor, limit: Int): CursorPage<PriceTick>
}
