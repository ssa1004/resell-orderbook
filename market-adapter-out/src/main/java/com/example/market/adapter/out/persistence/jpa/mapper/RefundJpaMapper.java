package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.RefundJpaEntity;
import com.example.market.domain.settlement.Refund;
import com.example.market.domain.settlement.RefundId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

import java.util.Currency;

public final class RefundJpaMapper {

    private RefundJpaMapper() {}

    public static RefundJpaEntity toEntity(Refund r) {
        RefundJpaEntity e = new RefundJpaEntity();
        e.setId(r.id().value());
        e.setTradeId(r.tradeId().value());
        e.setBuyerId(r.buyerId().value());
        e.setAmount(r.amount().amount());
        e.setCurrencyCode(r.amount().currency().getCurrencyCode());
        e.setReason(r.reason());
        e.setStatus(r.status());
        e.setPgRefundId(r.pgRefundId());
        e.setRequestedAt(r.requestedAt());
        e.setCompletedAt(r.completedAt());
        e.setVersion(r.version());
        return e;
    }

    public static Refund toDomain(RefundJpaEntity e) {
        Currency c = Currency.getInstance(e.getCurrencyCode());
        return Refund.restore(
                new RefundId(e.getId()),
                new TradeId(e.getTradeId()),
                UserId.of(e.getBuyerId()),
                Money.of(e.getAmount(), c),
                e.getReason(), e.getStatus(), e.getPgRefundId(),
                e.getRequestedAt(), e.getCompletedAt(), e.getVersion());
    }
}
