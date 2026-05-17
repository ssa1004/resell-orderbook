package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.marketdata.OhlcPeriod
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * OHLC candle persistence.
 *
 * Setter 없음 — bucket 이 닫힌 후 INSERT 만, 변경 X. raw tick 도 append-only 라 OHLC 도 영구 불변.
 */
@Entity
@Table(
    name = "ohlc_candles",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_ohlc_sku_period_bucket",
            columnNames = ["sku_id", "period", "bucket_start"],
        ),
    ],
)
class OhlcCandleJpaEntity(

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(name = "sku_id", nullable = false)
    var skuId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 16)
    var period: OhlcPeriod? = null,

    @Column(name = "bucket_start", nullable = false)
    var bucketStart: Instant? = null,

    @Column(name = "open_amount", nullable = false, precision = 18, scale = 0)
    var openAmount: BigDecimal? = null,

    @Column(name = "high_amount", nullable = false, precision = 18, scale = 0)
    var highAmount: BigDecimal? = null,

    @Column(name = "low_amount", nullable = false, precision = 18, scale = 0)
    var lowAmount: BigDecimal? = null,

    @Column(name = "close_amount", nullable = false, precision = 18, scale = 0)
    var closeAmount: BigDecimal? = null,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String? = null,

    @Column(name = "volume", nullable = false)
    var volume: Long = 0,

    @Column(name = "trade_count", nullable = false)
    var tradeCount: Long = 0,
)
