package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.settlement.RefundStatus;
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
@Table(name = "refunds", indexes = {
        @Index(name = "ix_refund_trade", columnList = "trade_id"),
        @Index(name = "ix_refund_status", columnList = "status, requested_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RefundJpaEntity {

    @Id
    private UUID id;

    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    @Column(name = "buyer_id", nullable = false, length = 64)
    private String buyerId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "pg_refund_id", length = 100)
    private String pgRefundId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
