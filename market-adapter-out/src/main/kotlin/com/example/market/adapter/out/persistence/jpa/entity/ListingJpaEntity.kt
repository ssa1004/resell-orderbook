package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.trading.ListingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Listing(ASK) row. (sku_id, status, expires_at, ask_price) 인덱스가 호가창 조회의 주 경로.
 */
@Entity
@Table(
    name = "listings",
    indexes = [
        Index(
            name = "ix_listing_orderbook",
            columnList = "sku_id, status, expires_at, ask_price, created_at",
        ),
        Index(name = "ix_listing_seller", columnList = "seller_id, status"),
    ],
)
class ListingJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "sku_id", nullable = false)
    var skuId: UUID? = null

    @Column(name = "seller_id", nullable = false, length = 64)
    var sellerId: String? = null

    @Column(name = "ask_price", nullable = false, precision = 19, scale = 0)
    var askPrice: BigDecimal? = null

    @Column(name = "currency_code", nullable = false, length = 3)
    var currencyCode: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ListingStatus? = null

    @Column(name = "matched_trade_id")
    var matchedTradeId: UUID? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
