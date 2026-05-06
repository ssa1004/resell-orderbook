package com.example.market.application.exception;

import com.example.market.domain.trading.ListingId;

public class ListingNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ListingNotFoundException(ListingId id) {
        super("listing not found: " + id);
    }
}
