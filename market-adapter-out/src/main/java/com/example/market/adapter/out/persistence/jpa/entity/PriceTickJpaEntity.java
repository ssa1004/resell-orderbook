package com.example.market.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * price_ticks row. PK 는 Snowflake 64bit long (ADR-0018) — 시간 순 정렬 가능.
 */
@Entity
@Table(name = "price_ticks")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class PriceTickJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "trade_id", nullable = false, unique = true)
    private UUID tradeId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "price_amount", nullable = false, precision = 18, scale = 0)
    private BigDecimal priceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
