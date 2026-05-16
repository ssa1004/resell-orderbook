package com.example.market.application.service

import com.example.market.application.command.PlaceListingCommand
import com.example.market.application.port.`in`.PlaceListingUseCase
import com.example.market.application.port.out.BidRepository
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.FeePolicyProvider
import com.example.market.application.port.out.ListingRepository
import com.example.market.application.port.out.OrderBookQueryPort
import com.example.market.application.port.out.PriceTickRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.SnowflakeIdGenerator
import com.example.market.domain.trading.Listing
import com.example.market.domain.trading.MatchEngine
import java.time.Clock
import java.util.Optional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 판매 호가(ASK) 등록 + 즉시 매칭 시도.
 *
 * 흐름 (모두 한 트랜잭션 안에서 처리):
 * 1. Idempotency-Key (같은 요청이 두 번 와도 한 번만 처리되게 막는 클라이언트 발급 키)
 *    점유 — Redis NX (이미 키가 있으면 거부) 로 중복 요청 차단
 * 2. `@Transactional` 시작
 * 3. [OrderBookQueryPort.acquireSkuLock] — SKU 단위 advisory lock (PG 의 응용 락).
 *    항상 같은 키로만 잠그므로 데드락이 구조적으로 발생할 수 없음
 * 4. 도메인 Listing 생성 + INSERT
 * 5. 같은 SKU 의 가장 높은 BID 를 FOR UPDATE SKIP LOCKED (다른 트랜잭션이 잠근 행은
 *    건너뛰고, 잠그지 않은 best 행만 잠근다) 로 조회
 * 6. [MatchEngine.matchNewAsk] — 매칭 검사 (만료/자기 거래/가격 조건을 모두 통과해야 함)
 * 7. 매칭 성공 시: Listing/Bid 를 MATCHED 로 마킹 + Trade INSERT + TradeMatched 이벤트
 *    실패 시: ListingPlaced 이벤트 (실시간 호가창 push 용)
 * 8. 트랜잭션 커밋 (Outbox 테이블 INSERT 도 같은 트랜잭션 — DB 변경과 이벤트 발행이 함께
 *    성공하거나 함께 실패)
 */
@Service
open class PlaceListingService(
    private val listings: ListingRepository,
    private val bids: BidRepository,
    private val trades: TradeRepository,
    private val orderBook: OrderBookQueryPort,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val feePolicyProvider: FeePolicyProvider,
    private val priceTicks: PriceTickRepository,
    private val priceTickIds: SnowflakeIdGenerator,
    private val clock: Clock,
) : PlaceListingUseCase {

    @Transactional
    override fun place(command: PlaceListingCommand): PlaceListingUseCase.PlaceListingResult {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)
        val now = clock.instant()

        // SKU 단위로 매칭을 한 줄로 줄세움 — pg_advisory_xact_lock(hash(sku_id))
        // (PostgreSQL 의 트랜잭션 단위 응용 락. 같은 키로만 잠가서 데드락 위험 없음)
        orderBook.acquireSkuLock(command.skuId)

        val listing = Listing.place(command.skuId, command.sellerId, command.askPrice, now)
        listings.save(listing)

        val highestBid = orderBook.findHighestBidForUpdate(command.skuId, now)
        val policy = feePolicyProvider.current()
        val trade = MatchEngine.matchNewAsk(listing, highestBid, policy, now)

        return if (trade.isPresent) {
            val t = trade.get()
            val bid = highestBid.orElseThrow()
            listing.markMatched(t.id)
            bid.markMatched(t.id)
            listings.save(listing)
            bids.save(bid)
            trades.save(t)
            events.publish(t.matched(now))
            // 시세 틱 (체결 단건 시세 데이터) 저장 — 거래와 같은 트랜잭션. 같은 거래가
            // 두 번 기록되는 경우는 DB 의 UNIQUE(trade_id) 제약이 차단한다.
            priceTicks.save(PriceTick.from(priceTickIds, t.id, t.skuId, t.price, now))
            log.info(
                "listing matched id={} trade={} price={} sku={}",
                listing.id, t.id, t.price, command.skuId,
            )
            PlaceListingUseCase.PlaceListingResult(listing.id, Optional.of(t.id))
        } else {
            events.publish(listing.placed(now))
            log.info(
                "listing placed (no match) id={} sku={} price={}",
                listing.id, command.skuId, command.askPrice,
            )
            PlaceListingUseCase.PlaceListingResult(listing.id, Optional.empty())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlaceListingService::class.java)
    }
}
