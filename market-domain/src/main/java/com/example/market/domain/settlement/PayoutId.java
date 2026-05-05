package com.example.market.domain.settlement;

import java.util.Objects;
import java.util.UUID;

public record PayoutId(UUID value) {
    public PayoutId { Objects.requireNonNull(value); }
    public static PayoutId newId() { return new PayoutId(UUID.randomUUID()); }
    public static PayoutId of(String s) { return new PayoutId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
