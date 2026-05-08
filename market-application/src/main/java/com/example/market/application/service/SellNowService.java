package com.example.market.application.service;

import com.example.market.application.command.SellNowCommand;
import com.example.market.application.port.in.SellNowUseCase;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.shared.SnowflakeIdGenerator;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.MatchEngine;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 즉시 판매. Highest BID 직접 매도. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SellNowService implements SellNowUseCase {

    private final BidRepository bids;
    private final TradeRepository trades;
    private final OrderBookQueryPort orderBook;
    private final EventPublisher events;
    private final IdempotentExecution idempotency;
    private final FeePolicyProvider feePolicyProvider;
    private final PriceTickRepository priceTicks;
    private final SnowflakeIdGenerator priceTickIds;
    private final Clock clock;

    @Override
    @Transactional
    public Trade sellNow(SellNowCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());
        Instant now = clock.instant();

        orderBook.acquireSkuLock(cmd.skuId());

        Bid bid = orderBook.findHighestBidForUpdate(cmd.skuId(), now)
                .orElseThrow(() -> new IllegalStateException("no active BID for sku " + cmd.skuId()));

        Trade trade = MatchEngine.sellNow(bid, cmd.sellerId(), feePolicyProvider.current(), now);
        bid.markMatched(trade.id());
        bids.save(bid);
        trades.save(trade);
        events.publish(trade.matched(now));
        // 시세 틱 — 같은 트랜잭션
        priceTicks.save(PriceTick.from(priceTickIds, trade.id(), trade.skuId(), trade.price(), now));
        log.info("sellNow matched trade={} seller={} bid={} price={}",
                trade.id(), cmd.sellerId(), bid.id(), trade.price());
        return trade;
    }
}
