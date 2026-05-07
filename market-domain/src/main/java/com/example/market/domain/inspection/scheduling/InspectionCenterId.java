package com.example.market.domain.inspection.scheduling;

import java.util.Objects;
import java.util.UUID;

public record InspectionCenterId(UUID value) {
    public InspectionCenterId { Objects.requireNonNull(value); }
    public static InspectionCenterId newId() { return new InspectionCenterId(UUID.randomUUID()); }
    public static InspectionCenterId of(String s) { return new InspectionCenterId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
