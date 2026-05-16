package com.example.market.application.service

import com.example.market.application.command.PlaceBidCommand
import com.example.market.application.port.`in`.PlaceBidUseCase
import com.example.market.application.port.out.BidRepository
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.FeePolicyProvider
import com.example.market.application.port.out.ListingRepository
import com.example.market.application.port.out.OrderBookQueryPort
import com.example.market.application.port.out.PriceTickRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.SnowflakeIdGenerator
import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.MatchEngine
import java.time.Clock
import java.util.Optional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 구매 호가(BID) 등록 + 즉시 매칭 시도. PlaceListingService 와 mirror. */
@Service
open class PlaceBidService(
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
) : PlaceBidUseCase {

    @Transactional
    override fun place(command: PlaceBidCommand): PlaceBidUseCase.PlaceBidResult {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)
        val now = clock.instant()

        orderBook.acquireSkuLock(command.skuId)

        val bid = Bid.place(command.skuId, command.buyerId, command.bidPrice, now)
        bids.save(bid)

        val lowestAsk = orderBook.findLowestAskForUpdate(command.skuId, now)
        val policy = feePolicyProvider.current()
        val trade = MatchEngine.matchNewBid(bid, lowestAsk, policy, now)

        return if (trade.isPresent) {
            val t = trade.get()
            val ask = lowestAsk.orElseThrow()
            bid.markMatched(t.id)
            ask.markMatched(t.id)
            bids.save(bid)
            listings.save(ask)
            trades.save(t)
            events.publish(t.matched(now))
            // 시세 틱 기록 (같은 트랜잭션 — 매칭과 원자적)
            priceTicks.save(PriceTick.from(priceTickIds, t.id, t.skuId, t.price, now))
            log.info(
                "bid matched id={} trade={} price={} sku={}",
                bid.id, t.id, t.price, command.skuId,
            )
            PlaceBidUseCase.PlaceBidResult(bid.id, Optional.of(t.id))
        } else {
            events.publish(bid.placed(now))
            log.info(
                "bid placed (no match) id={} sku={} price={}",
                bid.id, command.skuId, command.bidPrice,
            )
            PlaceBidUseCase.PlaceBidResult(bid.id, Optional.empty())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PlaceBidService::class.java)
    }
}
