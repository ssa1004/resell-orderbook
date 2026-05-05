package com.example.market.domain.inspection;

import java.util.Objects;
import java.util.UUID;

public record InspectionRequestId(UUID value) {
    public InspectionRequestId { Objects.requireNonNull(value); }
    public static InspectionRequestId newId() { return new InspectionRequestId(UUID.randomUUID()); }
    public static InspectionRequestId of(String s) { return new InspectionRequestId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
