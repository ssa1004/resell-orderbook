package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.PriceTickJpaEntity;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.PriceTick;
import com.example.market.domain.shared.Money;
import com.example.market.domain.trading.TradeId;

import java.util.Currency;

public final class PriceTickJpaMapper {

    private PriceTickJpaMapper() {}

    public static PriceTickJpaEntity toEntity(PriceTick t) {
        return new PriceTickJpaEntity(
                t.id(),
                t.tradeId().value(),
                t.skuId().value(),
                t.price().amount(),
                t.price().currency().getCurrencyCode(),
                t.occurredAt()
        );
    }

    public static PriceTick toDomain(PriceTickJpaEntity e) {
        return new PriceTick(
                e.getId(),
                TradeId.of(e.getTradeId().toString()),
                SkuId.of(e.getSkuId().toString()),
                Money.of(e.getPriceAmount(), Currency.getInstance(e.getCurrency())),
                e.getOccurredAt()
        );
    }
}
