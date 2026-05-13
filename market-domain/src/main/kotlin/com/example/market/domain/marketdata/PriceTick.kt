package com.example.market.domain.marketdata

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.SnowflakeIdGenerator
import com.example.market.domain.trading.TradeId
import java.time.Instant

/**
 * 한 SKU 의 한 거래 시점 가격 (append-only 시계열 record).
 *
 * **비유**: 주식의 *체결 틱* 과 같다 — "이 종목이 이 시각에 이 가격으로 체결됐다" 의 한 점.
 * 매칭이 일어날 때마다 1건씩 INSERT 만 됨. 수정/삭제 없음 (감사/차트 정합성).
 *
 * 이 값들이 모이면 시세 그래프 + OHLC 캔들스틱 + 24시간 통계 등이 모두 도출 가능 —
 * 사용자에게 보여주는 가격 차트의 가장 원시적인 raw 데이터가 바로 이 tick.
 *
 * **id 는 Snowflake 64bit long** (ADR-0018). UUID 대신 시간 정렬이 가능한 long 을 쓰는 이유:
 * - "WHERE id > cursor LIMIT N" 식의 cursor pagination 이 가능 — 차트 무한 스크롤
 * - 인덱스 page 가 timestamp 순으로 차곡차곡 쌓여 (write amplification 없음) DB 캐시 효율 ↑
 * - `id` 만으로 발급 시각 + 발급 인스턴스 디코딩 가능 → 모니터링 / 로그 분석에 유용
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor (`id()`,
 * `tradeId()`, `skuId()`, `price()`, `occurredAt()`) 를 노출해 호출자 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class PriceTick(
    val id: Long,
    val tradeId: TradeId,
    val skuId: SkuId,
    val price: Money,
    val occurredAt: Instant,
) {
    init {
        require(id > 0) { "id must be positive snowflake: $id" }
        require(price.isPositive) { "price must be positive: $price" }
    }

    companion object {
        /**
         * 매칭 직후 호출. id 는 [SnowflakeIdGenerator] 가 발급. 같은 trade 가 두 번 record 되면 안
         * 되므로 호출 측이 idempotency 책임 (보통 Trade 의 매칭 트랜잭션 안에서 1번만 호출).
         */
        @JvmStatic
        fun from(
            ids: SnowflakeIdGenerator,
            tradeId: TradeId,
            skuId: SkuId,
            price: Money,
            occurredAt: Instant,
        ): PriceTick = PriceTick(ids.nextId(), tradeId, skuId, price, occurredAt)
    }
}
