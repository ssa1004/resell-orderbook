package com.example.market.application.port.in;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.example.market.domain.marketdata.PriceTick;

import java.time.Instant;
import java.util.List;

/**
 * 시세 read API.
 *
 * <p>주식 시장의 *시세 표시 화면 + 차트 데이터* 와 같은 역할. 모두 read-only.</p>
 */
public interface MarketDataQueryUseCase {

    /** 한 SKU 의 현재 시세 카드 — last trade + best bid/ask + 24h 통계. */
    MarketStats currentStats(SkuId skuId);

    /**
     * 차트 / chart 데이터 — {@code [from, to)} 안의 체결 틱들. 시간 역순.
     * limit 으로 최대 개수 제어 (예: 차트 1000점).
     */
    List<PriceTick> ticks(SkuId skuId, Instant from, Instant to, int limit);
}
