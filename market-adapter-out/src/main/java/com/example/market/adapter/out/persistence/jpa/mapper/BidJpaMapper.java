package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.BidJpaEntity;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.BidId;
import com.example.market.domain.trading.TradeId;

import java.util.Currency;

public final class BidJpaMapper {

    private BidJpaMapper() {}

    public static BidJpaEntity toEntity(Bid b) {
        BidJpaEntity e = new BidJpaEntity();
        e.setId(b.id().value());
        e.setSkuId(b.skuId().value());
        e.setBuyerId(b.buyerId().value());
        e.setBidPrice(b.bidPrice().amount());
        e.setCurrencyCode(b.bidPrice().currency().getCurrencyCode());
        e.setStatus(b.status());
        e.setMatchedTradeId(b.matchedTradeId() != null ? b.matchedTradeId().value() : null);
        e.setExpiresAt(b.expiresAt());
        e.setCreatedAt(b.createdAt());
        e.setVersion(b.version());
        return e;
    }

    public static Bid toDomain(BidJpaEntity e) {
        Money price = Money.of(e.getBidPrice(), Currency.getInstance(e.getCurrencyCode()));
        TradeId matched = e.getMatchedTradeId() != null ? new TradeId(e.getMatchedTradeId()) : null;
        return Bid.restore(
                new BidId(e.getId()), new SkuId(e.getSkuId()),
                UserId.of(e.getBuyerId()), price,
                e.getExpiresAt(), e.getCreatedAt(),
                e.getStatus(), matched, e.getVersion());
    }
}
