package com.example.market.application.service

import com.example.market.application.command.SellNowCommand
import com.example.market.application.port.`in`.SellNowUseCase
import com.example.market.application.port.out.BidRepository
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.FeePolicyProvider
import com.example.market.application.port.out.OrderBookQueryPort
import com.example.market.application.port.out.PriceTickRepository
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.marketdata.PriceTick
import com.example.market.domain.shared.SnowflakeIdGenerator
import com.example.market.domain.trading.MatchEngine
import com.example.market.domain.trading.Trade
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 즉시 판매. Highest BID 직접 매도. */
@Service
open class SellNowService(
    private val bids: BidRepository,
    private val trades: TradeRepository,
    private val orderBook: OrderBookQueryPort,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val feePolicyProvider: FeePolicyProvider,
    private val priceTicks: PriceTickRepository,
    private val priceTickIds: SnowflakeIdGenerator,
    private val clock: Clock,
) : SellNowUseCase {

    @Transactional
    override fun sellNow(command: SellNowCommand): Trade {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)
        val now = clock.instant()

        orderBook.acquireSkuLock(command.skuId)

        val bid = orderBook.findHighestBidForUpdate(command.skuId, now)
            .orElseThrow { IllegalStateException("no active BID for sku ${command.skuId}") }

        val trade = MatchEngine.sellNow(bid, command.sellerId, feePolicyProvider.current(), now)
        bid.markMatched(trade.id)
        bids.save(bid)
        trades.save(trade)
        events.publish(trade.matched(now))
        // 시세 틱 — 같은 트랜잭션
        priceTicks.save(PriceTick.from(priceTickIds, trade.id, trade.skuId, trade.price, now))
        log.info(
            "sellNow matched trade={} seller={} bid={} price={}",
            trade.id, command.sellerId, bid.id, trade.price,
        )
        return trade
    }

    companion object {
        private val log = LoggerFactory.getLogger(SellNowService::class.java)
    }
}
