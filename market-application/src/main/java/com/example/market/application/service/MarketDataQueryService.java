package com.example.market.application.service;

import com.example.market.application.port.in.MarketDataQueryUseCase;
import com.example.market.application.port.in.OrderBookQueryUseCase;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.MarketStats;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 시세 read service. write side (매칭 / 호가 등록) 와 격리.
 *
 * <p>{@link #currentStats} 는 한 SKU 의 *현재 화면 카드* 를 만든다 — 호가창 best bid/ask
 * 와 가장 최근 체결가 + 24h 집계를 한 번의 응답으로 묶어 클라이언트가 추가 round-trip 없이
 * 받을 수 있게.</p>
 *
 * <p>트래픽 늘면 통계는 1분 단위 캐시 / 별도 read replica 로 분리 가능 — 인터페이스가 그 경계.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketDataQueryService implements MarketDataQueryUseCase {

    private static final Duration WINDOW_24H = Duration.ofHours(24);

    private final PriceTickRepository ticks;
    private final OrderBookQueryUseCase orderBookQuery;
    private final Clock clock;

    @Override
    public MarketStats currentStats(SkuId skuId) {
        Instant now = clock.instant();
        Instant from24h = now.minus(WINDOW_24H);

        Optional<PriceTick> last = ticks.findLatest(skuId);
        var orderBook = orderBookQuery.view(skuId, 1);
        var agg = ticks.aggregate(skuId, from24h, now);

        Money bestBid = orderBook.highestBid().orElse(null);
        Money bestAsk = orderBook.lowestAsk().orElse(null);
        Money spread = (bestBid != null && bestAsk != null) ? bestAsk.subtract(bestBid) : null;

        return new MarketStats(
                skuId,
                now,
                last.map(PriceTick::price).orElse(null),
                last.map(PriceTick::occurredAt).orElse(null),
                bestBid,
                bestAsk,
                spread,
                agg.count(),
                agg.min(),
                agg.avg(),
                agg.max()
        );
    }

    @Override
    public List<PriceTick> ticks(SkuId skuId, Instant from, Instant to, int limit) {
        return ticks.findBySkuInRange(skuId, from, to, limit);
    }
}
