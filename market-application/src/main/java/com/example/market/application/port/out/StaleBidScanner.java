package com.example.market.application.port.out;

import com.example.market.domain.trading.Bid;

import java.time.Instant;
import java.util.List;

public interface StaleBidScanner {
    List<Bid> findStaleActive(Instant cutoff, int batchSize);
}
