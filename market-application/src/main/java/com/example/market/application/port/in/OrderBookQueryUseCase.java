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

    /** 한 가격대의 호가 수량 (KREAM 호가창의 한 칸). */
    record PriceLevel(Money price, int count) {}
}
