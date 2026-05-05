package com.example.market.application.port.out;

import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeId;
import com.example.market.domain.trading.TradeStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradeRepository {
    void save(Trade trade);
    Optional<Trade> findById(TradeId id);

    /** TTL 만료된 CREATED 거래 (Spring Batch 용). */
    List<Trade> findStaleCreated(Instant cutoff);

    List<Trade> findByStatus(TradeStatus status, int limit);
}
