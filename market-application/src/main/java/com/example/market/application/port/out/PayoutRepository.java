package com.example.market.application.port.out;

import com.example.market.domain.settlement.Payout;
import com.example.market.domain.settlement.PayoutId;
import com.example.market.domain.trading.TradeId;

import java.util.Optional;

public interface PayoutRepository {
    void save(Payout payout);
    Optional<Payout> findById(PayoutId id);
    Optional<Payout> findByTradeId(TradeId tradeId);
}
