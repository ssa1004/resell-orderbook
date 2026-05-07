package com.example.market.domain.inspection.scheduling;

import java.util.Objects;
import java.util.UUID;

public record AppointmentId(UUID value) {
    public AppointmentId { Objects.requireNonNull(value); }
    public static AppointmentId newId() { return new AppointmentId(UUID.randomUUID()); }
    public static AppointmentId of(String s) { return new AppointmentId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
