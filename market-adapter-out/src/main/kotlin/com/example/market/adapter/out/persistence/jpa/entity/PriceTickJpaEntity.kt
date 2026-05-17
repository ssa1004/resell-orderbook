package com.example.market.adapter.out.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * price_ticks row. PK 는 Snowflake 64bit long (ADR-0018) — 시간 순 정렬 가능.
 */
@Entity
@Table(name = "price_ticks")
class PriceTickJpaEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null,

    @Column(name = "trade_id", nullable = false, unique = true)
    var tradeId: UUID? = null,

    @Column(name = "sku_id", nullable = false)
    var skuId: UUID? = null,

    @Column(name = "price_amount", nullable = false, precision = 18, scale = 0)
    var priceAmount: BigDecimal? = null,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String? = null,

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant? = null,
)
