package com.example.market.application.port.out;

import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.BidId;

import java.util.Optional;

public interface BidRepository {
    void save(Bid bid);
    Optional<Bid> findById(BidId id);
}
