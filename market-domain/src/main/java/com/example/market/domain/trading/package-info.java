/**
 * Trading — Listing(ASK), Bid, Trade. 매칭 엔진의 핵심 도메인.
 *
 * <p>호가창은 Listing/Bid 의 조회 모델로 다루고, 매칭은 같은 Sku 의 Lowest Ask 와 Highest Bid 가
 * 만날 때 {@link com.example.market.domain.trading.MatchEngine} 에서 결정한다.</p>
 */
@org.springframework.modulith.NamedInterface("trading")
package com.example.market.domain.trading;
