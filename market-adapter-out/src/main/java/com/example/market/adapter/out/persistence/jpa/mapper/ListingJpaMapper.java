package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.ListingJpaEntity;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.ListingId;
import com.example.market.domain.trading.TradeId;

import java.util.Currency;

public final class ListingJpaMapper {

    private ListingJpaMapper() {}

    public static ListingJpaEntity toEntity(Listing l) {
        ListingJpaEntity e = new ListingJpaEntity();
        e.setId(l.id().value());
        e.setSkuId(l.skuId().value());
        e.setSellerId(l.sellerId().value());
        e.setAskPrice(l.askPrice().amount());
        e.setCurrencyCode(l.askPrice().currency().getCurrencyCode());
        e.setStatus(l.status());
        e.setMatchedTradeId(l.matchedTradeId() != null ? l.matchedTradeId().value() : null);
        e.setExpiresAt(l.expiresAt());
        e.setCreatedAt(l.createdAt());
        e.setVersion(l.version());
        return e;
    }

    public static Listing toDomain(ListingJpaEntity e) {
        Money price = Money.of(e.getAskPrice(), Currency.getInstance(e.getCurrencyCode()));
        TradeId matched = e.getMatchedTradeId() != null ? new TradeId(e.getMatchedTradeId()) : null;
        return Listing.restore(
                new ListingId(e.getId()), new SkuId(e.getSkuId()),
                UserId.of(e.getSellerId()), price,
                e.getExpiresAt(), e.getCreatedAt(),
                e.getStatus(), matched, e.getVersion());
    }
}
