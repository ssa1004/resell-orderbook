package com.example.market.application.port.out;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.shared.Money;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PriceTickRepository {

    /**
     * 새 체결 틱 저장. 같은 trade 의 두 번째 호출은 unique constraint 로 인해 실패 (의도) —
     * 호출 측이 한 트랜잭션에서 1번만 호출하면 안전.
     */
    void save(PriceTick tick);

    /** 차트 / chart 데이터 — 시간 역순. limit 으로 길이 제어. */
    List<PriceTick> findBySkuInRange(SkuId skuId, Instant from, Instant to, int limit);

    /** 가장 최근 체결 1건 — last trade 표시용. 없으면 empty. */
    Optional<PriceTick> findLatest(SkuId skuId);

    /**
     * {@code [from, to)} 구간의 통계 한 번에. 결과의 {@code count == 0} 이면 다른 필드는 null.
     * 가장 자주 쓰이는 24h 통계용.
     */
    PriceAggregation aggregate(SkuId skuId, Instant from, Instant to);

    /**
     * {@code [from, to)} 안에 1개 이상 tick 이 있는 SKU 목록 — OHLC aggregation batch 가
     * "어떤 SKU 의 candle 을 새로 만들어야 하나" 결정하는 데 사용. 거래 없던 SKU 는 candle 없음.
     */
    List<SkuId> findDistinctSkuIdsInRange(Instant from, Instant to);

    record PriceAggregation(long count, Money min, Money avg, Money max) {}
}
