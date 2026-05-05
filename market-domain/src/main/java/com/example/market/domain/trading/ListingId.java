package com.example.market.domain.trading;

import java.util.Objects;
import java.util.UUID;

public record ListingId(UUID value) {
    public ListingId { Objects.requireNonNull(value); }
    public static ListingId newId() { return new ListingId(UUID.randomUUID()); }
    public static ListingId of(String s) { return new ListingId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
