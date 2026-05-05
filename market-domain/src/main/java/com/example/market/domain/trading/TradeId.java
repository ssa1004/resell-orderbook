package com.example.market.domain.trading;

import java.util.Objects;
import java.util.UUID;

public record TradeId(UUID value) {
    public TradeId { Objects.requireNonNull(value); }
    public static TradeId newId() { return new TradeId(UUID.randomUUID()); }
    public static TradeId of(String s) { return new TradeId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
