package com.example.market.domain.marketdata;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 한 SKU × 한 시간 단위 (period) × 한 bucket 의 OHLC 집계.
 *
 * <p>각 필드 의미 — *주식 차트의 캔들스틱 한 막대*:
 * <ul>
 *   <li>{@code open}  — bucket 안 *첫* 체결가</li>
 *   <li>{@code high}  — bucket 안 *최대* 체결가</li>
 *   <li>{@code low}   — bucket 안 *최소* 체결가</li>
 *   <li>{@code close} — bucket 안 *마지막* 체결가</li>
 *   <li>{@code volume} / {@code tradeCount} — 거래량 / 건수</li>
 * </ul>
 *
 * <p><b>append-only + UNIQUE</b>: bucket 이 닫힌 후 (시간 지나서 더 이상 새 tick 안 들어옴)
 * 딱 1번 INSERT. 같은 (sku, period, bucketStart) 가 다시 들어오면 DB constraint 가 거절 →
 * 배치 멱등성. raw tick 은 변하지 않으므로 OHLC 도 한 번 계산되면 영구.</p>
 */
public record OhlcCandle(
        UUID id,
        SkuId skuId,
        OhlcPeriod period,
        Instant bucketStart,
        Money open,
        Money high,
        Money low,
        Money close,
        long volume,
        long tradeCount
) {

    public OhlcCandle {
        Objects.requireNonNull(id);
        Objects.requireNonNull(skuId);
        Objects.requireNonNull(period);
        Objects.requireNonNull(bucketStart);
        Objects.requireNonNull(open);
        Objects.requireNonNull(high);
        Objects.requireNonNull(low);
        Objects.requireNonNull(close);
        if (volume < 0 || tradeCount < 0) {
            throw new IllegalArgumentException("volume/tradeCount must be non-negative");
        }
        // OHLC invariant — low ≤ open/close ≤ high
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("low > high (impossible candle)");
        }
        if (open.compareTo(low) < 0 || open.compareTo(high) > 0) {
            throw new IllegalArgumentException("open outside [low,high]");
        }
        if (close.compareTo(low) < 0 || close.compareTo(high) > 0) {
            throw new IllegalArgumentException("close outside [low,high]");
        }
    }

    /**
     * PriceTick 묶음 (이미 같은 SKU × bucket 으로 필터링된) → 1개 OHLC.
     *
     * <p>호출자 (aggregation service) 가 *bucket 단위로 그룹화* 한 후 호출. 같은 bucket
     * 안의 tick 들 사이에 통화는 모두 같다고 가정 (한정판 sneaker = 단일 통화).</p>
     */
    public static OhlcCandle from(SkuId skuId, OhlcPeriod period, Instant bucketStart,
                                  List<PriceTick> ticks) {
        if (ticks.isEmpty()) {
            throw new IllegalArgumentException("cannot build OHLC from empty tick list");
        }
        // 시간순 정렬해 open/close 결정 — 호출자가 정렬했어도 방어적으로 한 번 더.
        List<PriceTick> sorted = ticks.stream()
                .sorted(Comparator.comparing(PriceTick::occurredAt))
                .toList();

        Money open = sorted.get(0).price();
        Money close = sorted.get(sorted.size() - 1).price();
        Money high = sorted.stream().map(PriceTick::price).max(Money::compareTo).orElseThrow();
        Money low  = sorted.stream().map(PriceTick::price).min(Money::compareTo).orElseThrow();

        return new OhlcCandle(
                UUID.randomUUID(),
                skuId, period, bucketStart,
                open, high, low, close,
                volumeFromTicks(sorted),
                sorted.size()
        );
    }

    /**
     * 한정판 reseller 의 거래 단위는 모두 1 (한 켤레/한 점) — volume == tradeCount.
     * 다른 도메인 (가변 수량) 으로 확장되면 이 메서드만 바꿈.
     */
    private static long volumeFromTicks(List<PriceTick> ticks) {
        return ticks.size();
    }

    /** Frontend 가 캔들의 색을 정할 때 (close > open = 상승, 빨간 양봉). */
    public boolean isRising() {
        return close.compareTo(open) > 0;
    }

    /** 단순 통계 — close - open (변동 폭). 음수면 하락. */
    public BigDecimal changeAmount() {
        return close.amount().subtract(open.amount());
    }

    /** % 변동 — (close - open) / open * 100. open 이 0 이면 0. */
    public BigDecimal changePercent() {
        if (open.amount().signum() == 0) return BigDecimal.ZERO;
        return close.amount().subtract(open.amount())
                .divide(open.amount(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
