package com.example.market.application.service

import com.example.market.application.command.BuyNowCommand
import com.example.market.application.port.`in`.BuyNowUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.FeePolicyProvider
import com.example.market.application.port.out.ListingRepository
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

/**
 * 즉시 구매. 사용자가 BID 등록 없이 Lowest ASK 를 직접 매수.
 *
 * [MatchEngine.buyNow] 가 합성 BID 를 만들고 매칭 (가격 = ASK 가격).
 */
@Service
open class BuyNowService(
    private val listings: ListingRepository,
    private val trades: TradeRepository,
    private val orderBook: OrderBookQueryPort,
    private val events: EventPublisher,
    private val idempotency: IdempotentExecution,
    private val feePolicyProvider: FeePolicyProvider,
    private val priceTicks: PriceTickRepository,
    private val priceTickIds: SnowflakeIdGenerator,
    private val clock: Clock,
) : BuyNowUseCase {

    @Transactional
    override fun buyNow(command: BuyNowCommand): Trade {
        idempotency.acquireAndReleaseOnRollback(command.idempotencyKey)
        val now = clock.instant()

        orderBook.acquireSkuLock(command.skuId)

        val ask = orderBook.findLowestAskForUpdate(command.skuId, now)
            .orElseThrow { IllegalStateException("no active ASK for sku ${command.skuId}") }

        val trade = MatchEngine.buyNow(ask, command.buyerId, feePolicyProvider.current(), now)
        ask.markMatched(trade.id)
        listings.save(ask)
        trades.save(trade)
        events.publish(trade.matched(now))
        // 시세 틱 — 같은 트랜잭션
        priceTicks.save(PriceTick.from(priceTickIds, trade.id, trade.skuId, trade.price, now))
        log.info(
            "buyNow matched trade={} buyer={} ask={} price={}",
            trade.id, command.buyerId, ask.id, trade.price,
        )
        return trade
    }

    companion object {
        private val log = LoggerFactory.getLogger(BuyNowService::class.java)
    }
}
