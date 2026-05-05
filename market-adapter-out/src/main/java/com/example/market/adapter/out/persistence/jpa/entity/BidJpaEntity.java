package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.trading.BidStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bids", indexes = {
        @Index(name = "ix_bid_orderbook",
                columnList = "sku_id, status, expires_at, bid_price, created_at"),
        @Index(name = "ix_bid_buyer", columnList = "buyer_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class BidJpaEntity {

    @Id
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "buyer_id", nullable = false, length = 64)
    private String buyerId;

    @Column(name = "bid_price", nullable = false, precision = 19, scale = 0)
    private BigDecimal bidPrice;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BidStatus status;

    @Column(name = "matched_trade_id")
    private UUID matchedTradeId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;
}
