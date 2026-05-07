package com.example.market.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inspection_centers")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class InspectionCenterJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "address", nullable = false, length = 512)
    private String address;

    @Column(name = "parallel_capacity", nullable = false)
    private int parallelCapacity;

    @Column(name = "slot_duration_minutes", nullable = false)
    private long slotDurationMinutes;

    @Column(name = "booking_lead_time_minutes", nullable = false)
    private long bookingLeadTimeMinutes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
