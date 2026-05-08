package com.example.market.application.service;

import com.example.market.application.command.PlaceListingCommand;
import com.example.market.application.port.in.PlaceListingUseCase;
import com.example.market.application.port.out.BidRepository;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.FeePolicyProvider;
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
 * <p>흐름 (모두 한 트랜잭션 안에서 처리):</p>
 * <ol>
 *   <li>Idempotency-Key (같은 요청이 두 번 와도 한 번만 처리되게 막는 클라이언트 발급 키)
 *       점유 — Redis NX (이미 키가 있으면 거부) 로 중복 요청 차단</li>
 *   <li>{@code @Transactional} 시작</li>
 *   <li>{@link OrderBookQueryPort#acquireSkuLock} — SKU 단위 advisory lock (PG 의 응용 락).
 *       항상 같은 키로만 잠그므로 데드락이 구조적으로 발생할 수 없음</li>
 *   <li>도메인 Listing 생성 + INSERT</li>
 *   <li>같은 SKU 의 가장 높은 BID 를 FOR UPDATE SKIP LOCKED (다른 트랜잭션이 잠근 행은
 *       건너뛰고, 잠그지 않은 best 행만 잠근다) 로 조회</li>
 *   <li>{@link MatchEngine#matchNewAsk} — 매칭 검사 (만료/자기 거래/가격 조건을 모두 통과해야 함)</li>
 *   <li>매칭 성공 시: Listing/Bid 를 MATCHED 로 마킹 + Trade INSERT + TradeMatched 이벤트
 *       <br>실패 시: ListingPlaced 이벤트 (실시간 호가창 push 용)</li>
 *   <li>트랜잭션 커밋 (Outbox 테이블 INSERT 도 같은 트랜잭션 — DB 변경과 이벤트 발행이 함께
 *       성공하거나 함께 실패)</li>
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
    private final IdempotentExecution idempotency;
    private final FeePolicyProvider feePolicyProvider;
    private final PriceTickRepository priceTicks;
    private final Clock clock;

    @Override
    @Transactional
    public PlaceListingResult place(PlaceListingCommand cmd) {
        idempotency.acquireAndReleaseOnRollback(cmd.idempotencyKey());
        Instant now = clock.instant();

        // SKU 단위로 매칭을 한 줄로 줄세움 — pg_advisory_xact_lock(hash(sku_id))
        // (PostgreSQL 의 트랜잭션 단위 응용 락. 같은 키로만 잠가서 데드락 위험 없음)
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
            // 시세 틱 (체결 단건 시세 데이터) 저장 — 거래와 같은 트랜잭션. 같은 거래가
            // 두 번 기록되는 경우는 DB 의 UNIQUE(trade_id) 제약이 차단한다.
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
