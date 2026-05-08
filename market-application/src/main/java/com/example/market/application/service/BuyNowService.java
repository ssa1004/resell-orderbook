package com.example.market.application.service;

import com.example.market.application.command.BuyNowCommand;
import com.example.market.application.port.in.BuyNowUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.MatchEngine;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 즉시 구매. 사용자가 BID 등록 없이 Lowest ASK 를 직접 매수.
 *
 * <p>{@link MatchEngine#buyNow} 가 합성 BID 를 만들고 매칭 (가격 = ASK 가격).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyNowService implements BuyNowUseCase {

    private final ListingRepository listings;
    private final TradeRepository trades;
    private final OrderBookQueryPort orderBook;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final FeePolicyProvider feePolicyProvider;
    private final PriceTickRepository priceTicks;
    private final Clock clock;

    @Override
    @Transactional
    public Trade buyNow(BuyNowCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());
        Instant now = clock.instant();

        orderBook.acquireSkuLock(cmd.skuId());

        Listing ask = orderBook.findLowestAskForUpdate(cmd.skuId(), now)
                .orElseThrow(() -> new IllegalStateException("no active ASK for sku " + cmd.skuId()));

        Trade trade = MatchEngine.buyNow(ask, cmd.buyerId(), feePolicyProvider.current(), now);
        ask.markMatched(trade.id());
        listings.save(ask);
        trades.save(trade);
        events.publish(trade.matched(now));
        // 시세 틱 — 같은 트랜잭션
        priceTicks.save(PriceTick.from(trade.id(), trade.skuId(), trade.price(), now));
        log.info("buyNow matched trade={} buyer={} ask={} price={}",
                trade.id(), cmd.buyerId(), ask.id(), trade.price());
        return trade;
    }
}
