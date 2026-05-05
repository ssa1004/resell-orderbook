package com.example.market.domain.catalog;

import java.util.Objects;
import java.util.UUID;

public record ProductId(UUID value) {
    public ProductId { Objects.requireNonNull(value); }
    public static ProductId newId() { return new ProductId(UUID.randomUUID()); }
    public static ProductId of(String s) { return new ProductId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
