package com.example.market.domain.catalog;

import java.util.Objects;
import java.util.UUID;

public record SkuId(UUID value) {
    public SkuId { Objects.requireNonNull(value); }
    public static SkuId newId() { return new SkuId(UUID.randomUUID()); }
    public static SkuId of(String s) { return new SkuId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
