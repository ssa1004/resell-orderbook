package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.settlement.RefundStatus
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
    name = "refunds",
    indexes = [
        Index(name = "ix_refund_trade", columnList = "trade_id"),
        Index(name = "ix_refund_status", columnList = "status, requested_at"),
    ],
)
class RefundJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "trade_id", nullable = false)
    var tradeId: UUID? = null

    @Column(name = "buyer_id", nullable = false, length = 64)
    var buyerId: String? = null

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    var amount: BigDecimal? = null

    @Column(name = "currency_code", nullable = false, length = 3)
    var currencyCode: String? = null

    @Column(name = "reason", length = 500)
    var reason: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: RefundStatus? = null

    @Column(name = "pg_refund_id", length = 100)
    var pgRefundId: String? = null

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
