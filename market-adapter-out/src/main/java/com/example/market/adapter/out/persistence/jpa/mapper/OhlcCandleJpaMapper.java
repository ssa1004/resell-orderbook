package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.OhlcCandleJpaEntity;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.marketdata.OhlcCandle;
import com.example.market.domain.shared.Money;

import java.util.Currency;

public final class OhlcCandleJpaMapper {

    private OhlcCandleJpaMapper() {}

    public static OhlcCandleJpaEntity toEntity(OhlcCandle c) {
        return new OhlcCandleJpaEntity(
                c.id(),
                c.skuId().value(),
                c.period(),
                c.bucketStart(),
                c.open().amount(),
                c.high().amount(),
                c.low().amount(),
                c.close().amount(),
                c.open().currency().getCurrencyCode(),
                c.volume(),
                c.tradeCount()
        );
    }

    public static OhlcCandle toDomain(OhlcCandleJpaEntity e) {
        Currency currency = Currency.getInstance(e.getCurrency());
        return new OhlcCandle(
                e.getId(),
                SkuId.of(e.getSkuId().toString()),
                e.getPeriod(),
                e.getBucketStart(),
                Money.of(e.getOpenAmount(), currency),
                Money.of(e.getHighAmount(), currency),
                Money.of(e.getLowAmount(), currency),
                Money.of(e.getCloseAmount(), currency),
                e.getVolume(),
                e.getTradeCount()
        );
    }
}
