package com.example.market.application.service;

import com.example.market.application.command.PlaceBidCommand;
import com.example.market.application.port.in.PlaceBidUseCase;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.SnowflakeIdGenerator;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.MatchEngine;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/** 구매 호가(BID) 등록 + 즉시 매칭 시도. PlaceListingService 와 mirror. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlaceBidService implements PlaceBidUseCase {

    private final ListingRepository listings;
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
    public PlaceBidResult place(PlaceBidCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());
        Instant now = clock.instant();

        orderBook.acquireSkuLock(cmd.skuId());

        Bid bid = Bid.place(cmd.skuId(), cmd.buyerId(), cmd.bidPrice(), now);
        bids.save(bid);

        Optional<Listing> lowestAsk = orderBook.findLowestAskForUpdate(cmd.skuId(), now);
        FeePolicy policy = feePolicyProvider.current();
        Optional<Trade> trade = MatchEngine.matchNewBid(bid, lowestAsk, policy, now);

        if (trade.isPresent()) {
            Trade t = trade.get();
            Listing ask = lowestAsk.orElseThrow();
            bid.markMatched(t.id());
            ask.markMatched(t.id());
            bids.save(bid);
            listings.save(ask);
            trades.save(t);
            events.publish(t.matched(now));
            // 시세 틱 기록 (같은 트랜잭션 — 매칭과 원자적)
            priceTicks.save(PriceTick.from(priceTickIds, t.id(), t.skuId(), t.price(), now));
            log.info("bid matched id={} trade={} price={} sku={}",
                    bid.id(), t.id(), t.price(), cmd.skuId());
            return new PlaceBidResult(bid.id(), Optional.of(t.id()));
        } else {
            events.publish(bid.placed(now));
            log.info("bid placed (no match) id={} sku={} price={}",
                    bid.id(), cmd.skuId(), cmd.bidPrice());
            return new PlaceBidResult(bid.id(), Optional.empty());
        }
    }
}
