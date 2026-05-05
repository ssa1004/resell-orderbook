package com.example.market.domain.settlement;

import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

import java.time.Instant;
import java.util.Objects;

/**
 * Refund — 검수 실패 시 구매자에게 *전액* 환불.
 *
 * <p>{@code amount} 는 구매자가 결제 시점에 PG 에 결제한 총액 ({@link FeeSnapshot#buyerCharge()}) —
 * 즉 거래가 + 구매자 수수료 + 검수비 + 배송비 모두 포함. 검수 실패는 검수센터/판매자 책임이므로
 * 구매자는 부담한 모든 비용을 돌려받음 (KREAM 모델).</p>
 *
 * <p>흐름: Trade.startRefunding() → Refund.request() → PG.refund() 성공 시 Refund.complete() →
 * Trade.closeAsFailedAfterRefund(). 환불 실패 (PG 응답 실패) 시 Refund.fail() — 운영자 수동 처리.</p>
 */
public class Refund {

    private final RefundId id;
    private final TradeId tradeId;
    private final UserId buyerId;
    private final Money amount;
    private final String reason;
    private RefundStatus status;
    private String pgRefundId;
    private final Instant requestedAt;
    private Instant completedAt;
    private long version;

    private Refund(RefundId id, TradeId tradeId, UserId buyerId, Money amount, String reason,
                   RefundStatus status, String pgRefundId,
                   Instant requestedAt, Instant completedAt, long version) {
        this.id = id;
        this.tradeId = tradeId;
        this.buyerId = buyerId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.pgRefundId = pgRefundId;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    public static Refund request(TradeId tradeId, UserId buyerId, Money amount,
                                 String reason, Instant now) {
        Objects.requireNonNull(tradeId); Objects.requireNonNull(buyerId);
        Objects.requireNonNull(amount);
        if (!amount.isPositive()) throw new IllegalArgumentException("amount must be positive");
        return new Refund(RefundId.newId(), tradeId, buyerId, amount, reason,
                RefundStatus.REQUESTED, null, now, null, 0L);
    }

    public static Refund restore(RefundId id, TradeId tradeId, UserId buyerId, Money amount, String reason,
                                 RefundStatus status, String pgRefundId,
                                 Instant requestedAt, Instant completedAt, long version) {
        return new Refund(id, tradeId, buyerId, amount, reason, status, pgRefundId,
                requestedAt, completedAt, version);
    }

    public RefundCompleted complete(String pgRefundId, Instant now) {
        if (status != RefundStatus.REQUESTED) {
            throw new IllegalStateException("must be REQUESTED, was " + status);
        }
        this.status = RefundStatus.COMPLETED;
        this.pgRefundId = pgRefundId;
        this.completedAt = now;
        return new RefundCompleted(id, tradeId, buyerId, amount, pgRefundId, now);
    }

    public RefundFailed fail(String reason, Instant now) {
        if (status == RefundStatus.COMPLETED) throw new IllegalStateException("already COMPLETED");
        this.status = RefundStatus.FAILED;
        this.completedAt = now;
        return new RefundFailed(id, tradeId, buyerId, reason, now);
    }

    public RefundId id() { return id; }
    public TradeId tradeId() { return tradeId; }
    public UserId buyerId() { return buyerId; }
    public Money amount() { return amount; }
    public String reason() { return reason; }
    public RefundStatus status() { return status; }
    public String pgRefundId() { return pgRefundId; }
    public Instant requestedAt() { return requestedAt; }
    public Instant completedAt() { return completedAt; }
    public long version() { return version; }

    public record RefundCompleted(RefundId refundId, TradeId tradeId, UserId buyerId, Money amount,
                                  String pgRefundId, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return refundId.toString(); }
    }

    public record RefundFailed(RefundId refundId, TradeId tradeId, UserId buyerId, String reason,
                               Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return refundId.toString(); }
    }
}
