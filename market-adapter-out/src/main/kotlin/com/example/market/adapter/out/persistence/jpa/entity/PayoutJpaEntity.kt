package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.settlement.PayoutStatus
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
    name = "payouts",
    indexes = [
        Index(name = "ix_payout_trade", columnList = "trade_id", unique = true),
        Index(name = "ix_payout_seller_status", columnList = "seller_id, status"),
    ],
)
class PayoutJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "trade_id", nullable = false)
    var tradeId: UUID? = null

    @Column(name = "seller_id", nullable = false, length = 64)
    var sellerId: String? = null

    @Column(name = "trade_amount", nullable = false, precision = 19, scale = 0)
    var tradeAmount: BigDecimal? = null

    @Column(name = "seller_commission", nullable = false, precision = 19, scale = 0)
    var sellerCommission: BigDecimal? = null

    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 0)
    var processingFee: BigDecimal? = null

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 0)
    var netAmount: BigDecimal? = null

    @Column(name = "currency_code", nullable = false, length = 3)
    var currencyCode: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PayoutStatus? = null

    @Column(name = "bank_transfer_id", length = 100)
    var bankTransferId: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
