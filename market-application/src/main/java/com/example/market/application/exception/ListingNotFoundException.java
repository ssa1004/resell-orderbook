package com.example.market.application.exception;

import com.example.market.domain.trading.ListingId;

public class ListingNotFoundException extends RuntimeException {
    public ListingNotFoundException(ListingId id) {
        super("listing not found: " + id);
    }
}
