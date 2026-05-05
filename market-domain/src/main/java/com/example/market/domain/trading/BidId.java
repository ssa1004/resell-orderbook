package com.example.market.domain.trading;

import java.util.Objects;
import java.util.UUID;

public record BidId(UUID value) {
    public BidId { Objects.requireNonNull(value); }
    public static BidId newId() { return new BidId(UUID.randomUUID()); }
    public static BidId of(String s) { return new BidId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
