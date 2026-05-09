package com.example.market.application.port.in;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;

import java.util.List;
import java.util.Optional;

public interface OrderBookQueryUseCase {

    OrderBookView view(SkuId skuId, int depth);

    record OrderBookView(
            SkuId skuId,
            Optional<Money> lowestAsk,
            Optional<Money> highestBid,
            List<PriceLevel> asks,
            List<PriceLevel> bids
    ) {}

    /** 한 가격대의 호가 수량 (호가창 화면에서 한 칸을 차지하는 가격대 + 그 가격에 쌓인 호가 수). */
    record PriceLevel(Money price, int count) {}
}
