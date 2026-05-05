package com.example.market.application.service;

import com.example.market.application.port.in.OrderBookQueryUseCase;
import com.example.market.application.port.out.OrderBookQueryPort;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 호가창 view — read only. 같은 가격대의 호가를 PriceLevel 로 집계.
 */
@Service
@RequiredArgsConstructor
public class OrderBookQueryService implements OrderBookQueryUseCase {

    private final OrderBookQueryPort orderBook;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public OrderBookView view(SkuId skuId, int depth) {
        var now = clock.instant();
        List<Listing> asks = orderBook.topNAsks(skuId, depth, now);
        List<Bid> bids = orderBook.topNBids(skuId, depth, now);

        Optional<Money> lowestAsk = asks.stream().findFirst().map(Listing::askPrice);
        Optional<Money> highestBid = bids.stream().findFirst().map(Bid::bidPrice);

        return new OrderBookView(skuId, lowestAsk, highestBid,
                aggregateAsks(asks), aggregateBids(bids));
    }

    private List<PriceLevel> aggregateAsks(List<Listing> asks) {
        Map<Money, Integer> counts = new TreeMap<>();      // ASK 는 가격 ↑
        for (Listing a : asks) counts.merge(a.askPrice(), 1, Integer::sum);
        return counts.entrySet().stream()
                .map(e -> new PriceLevel(e.getKey(), e.getValue()))
                .toList();
    }

    private List<PriceLevel> aggregateBids(List<Bid> bids) {
        Map<Money, Integer> counts = new TreeMap<>(java.util.Comparator.reverseOrder()); // BID 는 가격 ↓
        for (Bid b : bids) counts.merge(b.bidPrice(), 1, Integer::sum);
        return counts.entrySet().stream()
                .map(e -> new PriceLevel(e.getKey(), e.getValue()))
                .toList();
    }
}
