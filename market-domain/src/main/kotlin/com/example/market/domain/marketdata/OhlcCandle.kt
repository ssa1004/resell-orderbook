package com.example.market.domain.marketdata

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * 한 SKU × 한 시간 단위(period) × 한 시간 구간(bucket) 의 OHLC 집계 = 캔들스틱 한 봉.
 * 주식/암호화폐 차트의 캔들 한 막대와 같은 형태.
 *
 * 각 필드 의미:
 * - `open` — 봉 시간 안의 첫 체결가 (시작가)
 * - `high` — 봉 시간 안의 최고 체결가
 * - `low` — 봉 시간 안의 최저 체결가
 * - `close` — 봉 시간 안의 마지막 체결가 (종가)
 * - `volume` / `tradeCount` — 거래량 / 거래 건수
 *
 * **append-only (한번 INSERT 후 절대 UPDATE/DELETE 안 함) + UNIQUE**: 봉이 닫힌 뒤
 * (시간이 지나 더 이상 새 tick 이 들어오지 않음) 정확히 1번 INSERT 한다. 같은
 * (sku, period, bucketStart) 가 다시 들어오면 DB UNIQUE 제약이 거절 → 배치를 다시 실행해도
 * 결과가 같다 (멱등). raw tick 자체가 안 변하니 OHLC 도 한 번 계산되면 영구히 그대로.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor 를 노출해 호출자
 * 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class OhlcCandle(
    val id: UUID,
    val skuId: SkuId,
    val period: OhlcPeriod,
    val bucketStart: Instant,
    val open: Money,
    val high: Money,
    val low: Money,
    val close: Money,
    val volume: Long,
    val tradeCount: Long,
) {
    init {
        require(volume >= 0 && tradeCount >= 0) { "volume/tradeCount must be non-negative" }
        // OHLC 의 항상 지켜져야 하는 규칙 (invariant) — low ≤ open/close ≤ high
        require(low.compareTo(high) <= 0) { "low > high (impossible candle)" }
        require(open.compareTo(low) >= 0 && open.compareTo(high) <= 0) { "open outside [low,high]" }
        require(close.compareTo(low) >= 0 && close.compareTo(high) <= 0) { "close outside [low,high]" }
    }

    /** 프론트엔드가 봉의 색을 정할 때 사용 (종가 > 시작가 = 상승 → 양봉). */
    fun isRising(): Boolean = close.compareTo(open) > 0

    /** 단순 통계 — 종가 - 시작가 (변동 폭). 음수면 하락. */
    fun changeAmount(): BigDecimal = close.amount().subtract(open.amount())

    /** 변동률 (%) — (종가 - 시작가) / 시작가 * 100. 시작가가 0 이면 0 반환. */
    fun changePercent(): BigDecimal {
        if (open.amount().signum() == 0) return BigDecimal.ZERO
        return close.amount().subtract(open.amount())
            .divide(open.amount(), 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
    }

    companion object {
        /**
         * PriceTick 묶음 (이미 같은 SKU × 같은 봉 시간 구간 으로 필터링된) 을 받아 OHLC 1개로 집계.
         *
         * 호출자 (집계 서비스) 가 봉 단위로 그룹화한 뒤 호출한다. 같은 봉 안의 tick 들의 통화는
         * 모두 같다고 가정 (한정판 sneaker = 단일 통화).
         */
        @JvmStatic
        fun from(
            skuId: SkuId,
            period: OhlcPeriod,
            bucketStart: Instant,
            ticks: List<PriceTick>,
        ): OhlcCandle {
            require(ticks.isNotEmpty()) { "cannot build OHLC from empty tick list" }
            // 시간순으로 정렬해서 시작가/종가를 결정한다 — 호출자가 이미 정렬해서 줬더라도
            // 안전을 위해 한 번 더 정렬 (방어적 코딩).
            val sorted = ticks.sortedBy { it.occurredAt }
            val open = sorted.first().price
            val close = sorted.last().price
            val high = sorted.maxOf { it.price }
            val low = sorted.minOf { it.price }
            return OhlcCandle(
                id = UUID.randomUUID(),
                skuId = skuId,
                period = period,
                bucketStart = bucketStart,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volumeFromTicks(sorted),
                tradeCount = sorted.size.toLong(),
            )
        }

        /**
         * 한정판 리셀러의 거래 단위는 모두 1 (한 켤레/한 점) 이므로 거래량 = 거래 건수.
         * 수량이 가변인 다른 도메인으로 확장하려면 이 메서드만 바꾸면 된다.
         */
        private fun volumeFromTicks(ticks: List<PriceTick>): Long = ticks.size.toLong()
    }
}
