package com.example.market.application.port.out;

import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.ListingId;

import java.util.Optional;

public interface ListingRepository {
    void save(Listing listing);
    Optional<Listing> findById(ListingId id);
}
