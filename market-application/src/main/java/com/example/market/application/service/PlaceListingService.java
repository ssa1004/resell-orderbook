package com.example.market.application.service;

import com.example.market.application.command.PlaceListingCommand;
import com.example.market.application.port.in.PlaceListingUseCase;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
import com.example.market.application.port.out.IdempotencyKeyStore;
import com.example.market.application.port.out.ListingRepository;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.application.port.out.PriceTickRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.settlement.FeePolicy;
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

/**
 * 판매 호가(ASK) 등록 + 즉시 매칭 시도.
 *
 * <p>흐름 (single transaction):</p>
 * <ol>
 *   <li>Idempotency-Key 점유 (Redis NX) — 중복 요청 차단</li>
 *   <li>{@code @Transactional} 시작</li>
 *   <li>{@link OrderBookQueryPort#acquireSkuLock} — SKU advisory lock (deadlock 결정적 회피)</li>
 *   <li>도메인 Listing 생성 + INSERT</li>
 *   <li>같은 SKU 의 Highest BID 를 FOR UPDATE SKIP LOCKED 로 조회</li>
 *   <li>{@link MatchEngine#matchNewAsk} — 매칭 검사 (만료/self-trade/가격 모두 거름)</li>
 *   <li>매칭 시: Listing/Bid markMatched + Trade INSERT + TradeMatched 이벤트
 *       <br>실패 시: ListingPlaced 이벤트 (호가창 갱신용)</li>
 *   <li>tx commit (Outbox INSERT 도 같은 tx — atomic)</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlaceListingService implements PlaceListingUseCase {

    private final ListingRepository listings;
    private final BidRepository bids;
    private final TradeRepository trades;
    private final OrderBookQueryPort orderBook;
    private final EventPublisher events;
    private final IdempotencyKeyStore idempotencyKeys;
    private final FeePolicyProvider feePolicyProvider;
    private final PriceTickRepository priceTicks;
    private final Clock clock;

    @Override
    @Transactional
    public PlaceListingResult place(PlaceListingCommand cmd) {
        idempotencyKeys.acquireOrThrow(cmd.idempotencyKey());
        Instant now = clock.instant();

        // SKU 단위 직렬화 — pg_advisory_xact_lock(hash(sku_id))
        orderBook.acquireSkuLock(cmd.skuId());

        Listing listing = Listing.place(cmd.skuId(), cmd.sellerId(), cmd.askPrice(), now);
        listings.save(listing);

        Optional<Bid> highestBid = orderBook.findHighestBidForUpdate(cmd.skuId(), now);
        FeePolicy policy = feePolicyProvider.current();
        Optional<Trade> trade = MatchEngine.matchNewAsk(listing, highestBid, policy, now);

        if (trade.isPresent()) {
            Trade t = trade.get();
            Bid bid = highestBid.orElseThrow();
            listing.markMatched(t.id());
            bid.markMatched(t.id());
            listings.save(listing);
            bids.save(bid);
            trades.save(t);
            events.publish(t.matched(now));
            // 시세 틱 — 같은 트랜잭션. 이중 저장은 unique(trade_id) 가 막음.
            priceTicks.save(PriceTick.from(t.id(), t.skuId(), t.price(), now));
            log.info("listing matched id={} trade={} price={} sku={}",
                    listing.id(), t.id(), t.price(), cmd.skuId());
            return new PlaceListingResult(listing.id(), Optional.of(t.id()));
        } else {
            events.publish(listing.placed(now));
            log.info("listing placed (no match) id={} sku={} price={}",
                    listing.id(), cmd.skuId(), cmd.askPrice());
            return new PlaceListingResult(listing.id(), Optional.empty());
        }
    }
}
