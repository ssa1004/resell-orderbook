package com.example.market.adapter.out.cache;

import com.example.market.application.port.out.MarketStatsCache;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 캐시 비활성 fallback (dev 기본). loader 를 그대로 호출 — 캐시 효과 없음.
 *
 * <p>{@code market.cache.redis-enabled=false} 일 때 활성. dev 환경은 인스턴스 1대 + 트래픽 적음 →
 * DB 가 충분히 빠르므로 캐시가 굳이 필요 없다. 운영에서는 {@link TwoTierMarketStatsCache} 가 활성.</p>
 */
@Component
@ConditionalOnProperty(name = "market.cache.redis-enabled", havingValue = "false", matchIfMissing = true)
public class PassThroughMarketStatsCache implements MarketStatsCache {

    @Override
    public MarketStats getOrCompute(SkuId key, Supplier<MarketStats> loader) {
        return loader.get();
    }

    @Override
    public void invalidate(SkuId key) {
        // no-op
    }
}
