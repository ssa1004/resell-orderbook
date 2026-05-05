/**
 * Trading — Listing(ASK), Bid, Trade. 매칭 엔진의 핵심 도메인.
 *
 * <p>{@link com.example.market.domain.trading.OrderBook} 가 (Sku 별) 호가창 invariant 를 갖는
 * aggregate root. 매칭은 같은 Sku 의 Lowest Ask 와 Highest Bid 가 만날 때 발생.</p>
 */
@org.springframework.modulith.NamedInterface("trading")
package com.example.market.domain.trading;
