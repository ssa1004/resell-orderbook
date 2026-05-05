package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.settlement.PayoutStatus;
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
@Table(name = "payouts", indexes = {
        @Index(name = "ix_payout_trade", columnList = "trade_id", unique = true),
        @Index(name = "ix_payout_seller_status", columnList = "seller_id, status")
})
@Getter
@Setter
@NoArgsConstructor
public class PayoutJpaEntity {

    @Id
    private UUID id;

    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    @Column(name = "seller_id", nullable = false, length = 64)
    private String sellerId;

    @Column(name = "trade_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal tradeAmount;

    @Column(name = "seller_commission", nullable = false, precision = 19, scale = 0)
    private BigDecimal sellerCommission;

    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 0)
    private BigDecimal processingFee;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal netAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutStatus status;

    @Column(name = "bank_transfer_id", length = 100)
    private String bankTransferId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
