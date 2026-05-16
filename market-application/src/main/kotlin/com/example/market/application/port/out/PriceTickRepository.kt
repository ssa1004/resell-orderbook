package com.example.market.application.port.out

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.Money
import java.time.Instant
import java.util.Optional

interface PriceTickRepository {

    /**
     * 새 체결 틱 저장. 같은 trade 의 두 번째 호출은 unique constraint 로 인해 실패 (의도) —
     * 호출 측이 한 트랜잭션에서 1번만 호출하면 안전.
     */
    fun save(tick: PriceTick)

    /** 차트 / chart 데이터 — 시간 역순. limit 으로 길이 제어. */
    fun findBySkuInRange(skuId: SkuId, from: Instant, to: Instant, limit: Int): List<PriceTick>

    /**
     * Cursor pagination — `afterId` 보다 큰 snowflake id 의 tick 을 id 오름차순으로.
     *
     * Snowflake id 가 시간 순으로 단조 증가하므로 (ADR-0018) "그 다음 페이지" 를 OFFSET 없이
     * id 비교로 잡을 수 있다. 무한 스크롤 / 실시간 차트 follow-up 에 적합.
     *
     * @param afterId 0 이면 처음부터
     */
    fun findBySkuAfter(skuId: SkuId, afterId: Long, limit: Int): List<PriceTick>

    /** 가장 최근 체결 1건 — last trade 표시용. 없으면 empty. */
    fun findLatest(skuId: SkuId): Optional<PriceTick>

    /**
     * `[from, to)` 구간의 통계 한 번에. 결과의 `count == 0` 이면 다른 필드는 null.
     * 가장 자주 쓰이는 24h 통계용.
     */
    fun aggregate(skuId: SkuId, from: Instant, to: Instant): PriceAggregation

    /**
     * `[from, to)` 안에 1개 이상 tick 이 있는 SKU 목록 — OHLC aggregation batch 가
     * "어떤 SKU 의 candle 을 새로 만들어야 하나" 결정하는 데 사용. 거래 없던 SKU 는 candle 없음.
     */
    fun findDistinctSkuIdsInRange(from: Instant, to: Instant): List<SkuId>

    @JvmRecord
    data class PriceAggregation(val count: Long, val min: Money?, val avg: Money?, val max: Money?)
}
