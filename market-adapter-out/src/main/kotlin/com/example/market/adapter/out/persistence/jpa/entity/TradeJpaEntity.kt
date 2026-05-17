package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.trading.TradeStatus
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
 * Trade row. FeeSnapshot 을 column 으로 펼쳐 저장 (정산/통계 query 직접 사용).
 */
@Entity
@Table(
    name = "trades",
    indexes = [
        Index(name = "ix_trade_status_created", columnList = "status, created_at"),
        Index(name = "ix_trade_seller", columnList = "seller_id"),
        Index(name = "ix_trade_buyer", columnList = "buyer_id"),
        Index(name = "ix_trade_listing", columnList = "listing_id"),
        Index(name = "ix_trade_bid", columnList = "bid_id"),
    ],
)
class TradeJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "sku_id", nullable = false)
    var skuId: UUID? = null

    @Column(name = "listing_id", nullable = false)
    var listingId: UUID? = null

    @Column(name = "bid_id", nullable = false)
    var bidId: UUID? = null

    @Column(name = "seller_id", nullable = false, length = 64)
    var sellerId: String? = null

    @Column(name = "buyer_id", nullable = false, length = 64)
    var buyerId: String? = null

    @Column(name = "price", nullable = false, precision = 19, scale = 0)
    var price: BigDecimal? = null

    @Column(name = "currency_code", nullable = false, length = 3)
    var currencyCode: String? = null

    // FeeSnapshot — 컬럼 펼침 (JSON 아닌 명시적 컬럼)
    @Column(name = "fee_seller_rate", nullable = false, precision = 5, scale = 2)
    var feeSellerRate: BigDecimal? = null

    @Column(name = "fee_buyer_rate", nullable = false, precision = 5, scale = 2)
    var feeBuyerRate: BigDecimal? = null

    @Column(name = "fee_inspection", nullable = false, precision = 19, scale = 0)
    var feeInspection: BigDecimal? = null

    @Column(name = "fee_shipping", nullable = false, precision = 19, scale = 0)
    var feeShipping: BigDecimal? = null

    @Column(name = "fee_processing", nullable = false, precision = 19, scale = 0)
    var feeProcessing: BigDecimal? = null

    @Column(name = "fee_seller_commission", nullable = false, precision = 19, scale = 0)
    var feeSellerCommission: BigDecimal? = null

    @Column(name = "fee_buyer_commission", nullable = false, precision = 19, scale = 0)
    var feeBuyerCommission: BigDecimal? = null

    @Column(name = "buyer_charge", nullable = false, precision = 19, scale = 0)
    var buyerCharge: BigDecimal? = null

    @Column(name = "seller_net", nullable = false, precision = 19, scale = 0)
    var sellerNet: BigDecimal? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: TradeStatus? = null

    @Column(name = "pg_payment_id", length = 100)
    var pgPaymentId: String? = null

    @Column(name = "inspection_fail_reason", length = 500)
    var inspectionFailReason: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
