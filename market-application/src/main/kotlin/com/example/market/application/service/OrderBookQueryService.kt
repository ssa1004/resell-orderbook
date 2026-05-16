package com.example.market.application.service

import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.application.port.out.OrderBookQueryPort
import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import com.example.market.domain.trading.Bid
import com.example.market.domain.trading.Listing
import java.time.Clock
import java.util.Optional
import java.util.TreeMap
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 호가창 view — read only. 같은 가격대의 호가를 PriceLevel 로 집계.
 */
@Service
@Transactional(readOnly = true)
open class OrderBookQueryService(
    private val orderBook: OrderBookQueryPort,
    private val clock: Clock,
) : OrderBookQueryUseCase {

    override fun view(skuId: SkuId, depth: Int): OrderBookQueryUseCase.OrderBookView {
        val now = clock.instant()
        val asks: List<Listing> = orderBook.topNAsks(skuId, depth, now)
        val bids: List<Bid> = orderBook.topNBids(skuId, depth, now)

        val lowestAsk: Optional<Money> = Optional.ofNullable(asks.firstOrNull()?.askPrice)
        val highestBid: Optional<Money> = Optional.ofNullable(bids.firstOrNull()?.bidPrice)

        return OrderBookQueryUseCase.OrderBookView(
            skuId, lowestAsk, highestBid,
            aggregateAsks(asks), aggregateBids(bids),
        )
    }

    private fun aggregateAsks(asks: List<Listing>): List<OrderBookQueryUseCase.PriceLevel> {
        val counts: TreeMap<Money, Int> = TreeMap()      // ASK 는 가격 ↑
        for (a in asks) counts.merge(a.askPrice, 1, Int::plus)
        return counts.entries.map { OrderBookQueryUseCase.PriceLevel(it.key, it.value) }
    }

    private fun aggregateBids(bids: List<Bid>): List<OrderBookQueryUseCase.PriceLevel> {
        val counts: TreeMap<Money, Int> = TreeMap(Comparator.reverseOrder())   // BID 는 가격 ↓
        for (b in bids) counts.merge(b.bidPrice, 1, Int::plus)
        return counts.entries.map { OrderBookQueryUseCase.PriceLevel(it.key, it.value) }
    }
}
