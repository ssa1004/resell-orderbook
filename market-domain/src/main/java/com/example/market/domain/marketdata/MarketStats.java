package com.example.market.domain.marketdata;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * 한 SKU 의 *현재 시세 요약* — 운영 화면 / 사용자 대시보드 / API 응답에 표시되는 한 카드.
 *
 * <p>저장하지 않는 값 객체. application service 가 PriceTick 들 + OrderBook 의 best bid/ask
 * 를 합산해 만들어 반환한다 (캐시는 호출자 책임).</p>
 *
 * <p><b>각 필드의 의미</b>:
 * <ul>
 *   <li>{@code lastTradePrice / lastTradeAt} — 가장 최근 체결가. 차트의 '오른쪽 끝 점'.</li>
 *   <li>{@code bestBid / bestAsk / spread} — 호가창의 가장 좋은 매수/매도 호가 + 차이. spread 가
 *       작을수록 *유동성* 좋음.</li>
 *   <li>{@code last24hVolume} — 24시간 누적 체결 건수 (수량은 한정판이라 1당 1).</li>
 *   <li>{@code last24hMin / Avg / Max} — 24시간 가격 통계. 변동성 / 시세 추세 파악.</li>
 * </ul>
 */
public record MarketStats(
        SkuId skuId,
        Instant asOf,
        Money lastTradePrice,        // null = 아직 체결 없음
        Instant lastTradeAt,         // null = 아직 체결 없음
        Money bestBid,               // null = bid 호가 없음
        Money bestAsk,               // null = ask 호가 없음
        Money spread,                // bestAsk - bestBid. 한쪽 없으면 null
        long last24hVolume,
        Money last24hMin,            // null = 24h 내 거래 없음
        Money last24hAvg,            // null = 24h 내 거래 없음
        Money last24hMax             // null = 24h 내 거래 없음
) {

    public MarketStats {
        Objects.requireNonNull(skuId);
        Objects.requireNonNull(asOf);
        if (last24hVolume < 0) {
            throw new IllegalArgumentException("volume must be non-negative");
        }
    }
}
