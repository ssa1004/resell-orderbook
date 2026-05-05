package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.trading.ListingStatus;
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

/**
 * Listing(ASK) row. OrderBook query 의 핵심 — (sku_id, status, expires_at, ask_price) 인덱스.
 */
@Entity
@Table(name = "listings", indexes = {
        @Index(name = "ix_listing_orderbook",
                columnList = "sku_id, status, expires_at, ask_price, created_at"),
        @Index(name = "ix_listing_seller", columnList = "seller_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class ListingJpaEntity {

    @Id
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "seller_id", nullable = false, length = 64)
    private String sellerId;

    @Column(name = "ask_price", nullable = false, precision = 19, scale = 0)
    private BigDecimal askPrice;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ListingStatus status;

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
