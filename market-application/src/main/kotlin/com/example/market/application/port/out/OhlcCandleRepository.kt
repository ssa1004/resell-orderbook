package com.example.market.application.port.out

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.marketdata.OhlcCandle
import com.example.market.domain.marketdata.OhlcPeriod
import java.time.Instant

interface OhlcCandleRepository {

    /**
     * 새 candle 저장. 같은 (sku, period, bucket) 가 이미 있으면 DB unique constraint 가 거절 →
     * 호출자가 catch (배치 재실행 시 멱등성).
     */
    fun save(candle: OhlcCandle)

    /** 차트 — `[from, to)` 안 candle 들. 시간 역순. */
    fun findBySkuInRange(
        skuId: SkuId,
        period: OhlcPeriod,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<OhlcCandle>
}
