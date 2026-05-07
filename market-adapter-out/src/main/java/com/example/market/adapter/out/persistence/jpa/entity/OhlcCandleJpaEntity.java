package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.marketdata.OhlcPeriod;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * OHLC candle persistence.
 *
 * <p><b>{@code @Setter} 없음</b> — bucket 이 닫힌 후 INSERT 만, 변경 X.
 * raw tick 도 append-only 라 OHLC 도 영구 불변.</p>
 */
@Entity
@Table(name = "ohlc_candles", uniqueConstraints = {
        @UniqueConstraint(name = "uq_ohlc_sku_period_bucket",
                columnNames = {"sku_id", "period", "bucket_start"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
public class OhlcCandleJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period", nullable = false, length = 16)
    private OhlcPeriod period;

    @Column(name = "bucket_start", nullable = false)
    private Instant bucketStart;

    @Column(name = "open_amount", nullable = false, precision = 18, scale = 0)
    private BigDecimal openAmount;

    @Column(name = "high_amount", nullable = false, precision = 18, scale = 0)
    private BigDecimal highAmount;

    @Column(name = "low_amount", nullable = false, precision = 18, scale = 0)
    private BigDecimal lowAmount;

    @Column(name = "close_amount", nullable = false, precision = 18, scale = 0)
    private BigDecimal closeAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "volume", nullable = false)
    private long volume;

    @Column(name = "trade_count", nullable = false)
    private long tradeCount;
}
