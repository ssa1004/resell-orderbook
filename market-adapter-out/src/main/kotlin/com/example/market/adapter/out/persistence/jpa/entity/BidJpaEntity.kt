package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.trading.BidStatus
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

@Entity
@Table(
    name = "bids",
    indexes = [
        Index(
            name = "ix_bid_orderbook",
            columnList = "sku_id, status, expires_at, bid_price, created_at",
        ),
        Index(name = "ix_bid_buyer", columnList = "buyer_id, status"),
    ],
)
class BidJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "sku_id", nullable = false)
    var skuId: UUID? = null

    @Column(name = "buyer_id", nullable = false, length = 64)
    var buyerId: String? = null

    @Column(name = "bid_price", nullable = false, precision = 19, scale = 0)
    var bidPrice: BigDecimal? = null

    @Column(name = "currency_code", nullable = false, length = 3)
    var currencyCode: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BidStatus? = null

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
