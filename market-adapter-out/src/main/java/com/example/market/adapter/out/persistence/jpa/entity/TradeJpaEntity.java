package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.trading.TradeStatus;
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
 * Trade row. FeeSnapshot 을 column 으로 펼쳐 저장 (정산/통계 query 직접 사용).
 */
@Entity
@Table(name = "trades", indexes = {
        @Index(name = "ix_trade_status_created", columnList = "status, created_at"),
        @Index(name = "ix_trade_seller", columnList = "seller_id"),
        @Index(name = "ix_trade_buyer", columnList = "buyer_id"),
        @Index(name = "ix_trade_listing", columnList = "listing_id"),
        @Index(name = "ix_trade_bid", columnList = "bid_id")
})
@Getter
@Setter
@NoArgsConstructor
public class TradeJpaEntity {

    @Id
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "seller_id", nullable = false, length = 64)
    private String sellerId;

    @Column(name = "buyer_id", nullable = false, length = 64)
    private String buyerId;

    @Column(name = "price", nullable = false, precision = 19, scale = 0)
    private BigDecimal price;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    // FeeSnapshot — 컬럼 펼침 (JSON 아닌 명시적 컬럼)
    @Column(name = "fee_seller_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal feeSellerRate;

    @Column(name = "fee_buyer_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal feeBuyerRate;

    @Column(name = "fee_inspection", nullable = false, precision = 19, scale = 0)
    private BigDecimal feeInspection;

    @Column(name = "fee_shipping", nullable = false, precision = 19, scale = 0)
    private BigDecimal feeShipping;

    @Column(name = "fee_processing", nullable = false, precision = 19, scale = 0)
    private BigDecimal feeProcessing;

    @Column(name = "fee_seller_commission", nullable = false, precision = 19, scale = 0)
    private BigDecimal feeSellerCommission;

    @Column(name = "fee_buyer_commission", nullable = false, precision = 19, scale = 0)
    private BigDecimal feeBuyerCommission;

    @Column(name = "buyer_charge", nullable = false, precision = 19, scale = 0)
    private BigDecimal buyerCharge;

    @Column(name = "seller_net", nullable = false, precision = 19, scale = 0)
    private BigDecimal sellerNet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TradeStatus status;

    @Column(name = "pg_payment_id", length = 100)
    private String pgPaymentId;

    @Column(name = "inspection_fail_reason", length = 500)
    private String inspectionFailReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
