package com.example.market.application.port.out;

import com.example.market.domain.settlement.Refund;
import com.example.market.domain.settlement.RefundId;
import com.example.market.domain.trading.TradeId;

import java.util.Optional;

public interface RefundRepository {
    void save(Refund refund);
    Optional<Refund> findById(RefundId id);
    Optional<Refund> findByTradeId(TradeId tradeId);
}
