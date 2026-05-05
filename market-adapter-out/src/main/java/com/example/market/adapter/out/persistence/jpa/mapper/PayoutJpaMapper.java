package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.PayoutJpaEntity;
import com.example.market.domain.settlement.Payout;
import com.example.market.domain.settlement.PayoutId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

import java.util.Currency;

public final class PayoutJpaMapper {

    private PayoutJpaMapper() {}

    public static PayoutJpaEntity toEntity(Payout p) {
        PayoutJpaEntity e = new PayoutJpaEntity();
        e.setId(p.id().value());
        e.setTradeId(p.tradeId().value());
        e.setSellerId(p.sellerId().value());
        e.setTradeAmount(p.tradeAmount().amount());
        e.setSellerCommission(p.sellerCommission().amount());
        e.setProcessingFee(p.processingFee().amount());
        e.setNetAmount(p.netAmount().amount());
        e.setCurrencyCode(p.tradeAmount().currency().getCurrencyCode());
        e.setStatus(p.status());
        e.setBankTransferId(p.bankTransferId());
        e.setCreatedAt(p.createdAt());
        e.setCompletedAt(p.completedAt());
        e.setVersion(p.version());
        return e;
    }

    public static Payout toDomain(PayoutJpaEntity e) {
        Currency c = Currency.getInstance(e.getCurrencyCode());
        return Payout.restore(
                new PayoutId(e.getId()),
                new TradeId(e.getTradeId()),
                UserId.of(e.getSellerId()),
                Money.of(e.getTradeAmount(), c),
                Money.of(e.getSellerCommission(), c),
                Money.of(e.getProcessingFee(), c),
                Money.of(e.getNetAmount(), c),
                e.getStatus(), e.getBankTransferId(),
                e.getCreatedAt(), e.getCompletedAt(), e.getVersion());
    }
}
