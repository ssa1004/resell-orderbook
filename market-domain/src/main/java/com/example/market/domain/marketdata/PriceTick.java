package com.example.market.domain.marketdata;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.SnowflakeIdGenerator;
import com.example.market.domain.trading.TradeId;

import java.time.Instant;
import java.util.Objects;

/**
 * 한 SKU 의 한 거래 시점 가격 (append-only 시계열 record).
 *
 * <p><b>비유</b>: 주식의 *체결 틱* 과 같다 — "이 종목이 이 시각에 이 가격으로 체결됐다" 의 한 점.
 * 매칭이 일어날 때마다 1건씩 INSERT 만 됨. 수정/삭제 없음 (감사/차트 정합성).</p>
 *
 * <p>이 값들이 모이면 시세 그래프 + OHLC 캔들스틱 + 24시간 통계 등이 모두 도출 가능.
 * Kream / StockX 의 가격 차트의 raw 데이터가 바로 이 tick.</p>
 *
 * <p><b>id 는 Snowflake 64bit long</b> (ADR-0018). UUID 대신 시간 정렬이 가능한 long 을 쓰는 이유:</p>
 * <ul>
 *   <li>"WHERE id &gt; cursor LIMIT N" 식의 cursor pagination 이 가능 — 차트 무한 스크롤</li>
 *   <li>인덱스 page 가 timestamp 순으로 차곡차곡 쌓여 (write amplification 없음) DB 캐시 효율 ↑</li>
 *   <li>{@code id} 만으로 발급 시각 + 발급 인스턴스 디코딩 가능 → 모니터링 / 로그 분석에 유용</li>
 * </ul>
 */
public record PriceTick(
        long id,
        TradeId tradeId,
        SkuId skuId,
        Money price,
        Instant occurredAt
) {

    public PriceTick {
        Objects.requireNonNull(tradeId);
        Objects.requireNonNull(skuId);
        Objects.requireNonNull(price);
        Objects.requireNonNull(occurredAt);
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive snowflake: " + id);
        }
        if (!price.isPositive()) {
            throw new IllegalArgumentException("price must be positive: " + price);
        }
    }

    /**
     * 매칭 직후 호출. id 는 {@link SnowflakeIdGenerator} 가 발급. 같은 trade 가 두 번 record 되면 안
     * 되므로 호출 측이 idempotency 책임 (보통 Trade 의 매칭 트랜잭션 안에서 1번만 호출).
     */
    public static PriceTick from(SnowflakeIdGenerator ids,
                                 TradeId tradeId,
                                 SkuId skuId,
                                 Money price,
                                 Instant occurredAt) {
        return new PriceTick(ids.nextId(), tradeId, skuId, price, occurredAt);
    }
}
