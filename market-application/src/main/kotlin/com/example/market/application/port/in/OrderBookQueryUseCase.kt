package com.example.market.application.port.`in`

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import java.util.Optional

interface OrderBookQueryUseCase {

    fun view(skuId: SkuId, depth: Int): OrderBookView

    @JvmRecord
    data class OrderBookView(
        val skuId: SkuId,
        val lowestAsk: Optional<Money>,
        val highestBid: Optional<Money>,
        val asks: List<PriceLevel>,
        val bids: List<PriceLevel>,
    )

    /** 한 가격대의 호가 수량 (호가창 화면에서 한 칸을 차지하는 가격대 + 그 가격에 쌓인 호가 수). */
    @JvmRecord
    data class PriceLevel(val price: Money, val count: Int)
}
