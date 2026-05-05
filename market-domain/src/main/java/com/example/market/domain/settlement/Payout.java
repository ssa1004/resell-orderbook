package com.example.market.domain.settlement;

import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

import java.time.Instant;
import java.util.Objects;

/**
 * Payout — 판매자 정산. Trade.COMPLETED 이벤트로 트리거.
 *
 * <p>구조: tradeAmount → sellerCommission + processingFee 차감 → netAmount → 판매자 계좌로 송금.</p>
 *
 * <p>금액들은 거래 시점에 freeze 된 {@link FeeSnapshot} 으로부터 받음.</p>
 */
public class Payout {

    private final PayoutId id;
    private final TradeId tradeId;
    private final UserId sellerId;
    private final Money tradeAmount;
    private final Money sellerCommission;
    private final Money processingFee;
    private final Money netAmount;
    private PayoutStatus status;
    private String bankTransferId;
    private final Instant createdAt;
    private Instant completedAt;
    private long version;

    private Payout(PayoutId id, TradeId tradeId, UserId sellerId, Money tradeAmount,
                   Money sellerCommission, Money processingFee, Money netAmount,
                   PayoutStatus status, String bankTransferId,
                   Instant createdAt, Instant completedAt, long version) {
        this.id = id;
        this.tradeId = tradeId;
        this.sellerId = sellerId;
        this.tradeAmount = tradeAmount;
        this.sellerCommission = sellerCommission;
        this.processingFee = processingFee;
        this.netAmount = netAmount;
        this.status = status;
        this.bankTransferId = bankTransferId;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    /** FeeSnapshot 으로부터 정산 일정을 만든다. */
    public static Payout schedule(TradeId tradeId, UserId sellerId, FeeSnapshot snapshot, Instant now) {
        Objects.requireNonNull(tradeId); Objects.requireNonNull(sellerId);
        Objects.requireNonNull(snapshot);
        if (snapshot.sellerNet().isNegative()) {
            throw new IllegalArgumentException("sellerNet must be >= 0, was " + snapshot.sellerNet());
        }
        return new Payout(PayoutId.newId(), tradeId, sellerId,
                snapshot.tradeAmount(),
                snapshot.sellerCommission(),
                snapshot.fixedProcessingFee(),
                snapshot.sellerNet(),
                PayoutStatus.SCHEDULED, null, now, null, 0L);
    }

    public static Payout restore(PayoutId id, TradeId tradeId, UserId sellerId, Money tradeAmount,
                                 Money sellerCommission, Money processingFee, Money netAmount,
                                 PayoutStatus status, String bankTransferId,
                                 Instant createdAt, Instant completedAt, long version) {
        return new Payout(id, tradeId, sellerId, tradeAmount, sellerCommission, processingFee,
                netAmount, status, bankTransferId, createdAt, completedAt, version);
    }

    public PayoutSent send(String bankTransferId, Instant now) {
        if (status != PayoutStatus.SCHEDULED) {
            throw new IllegalStateException("must be SCHEDULED to send, was " + status);
        }
        Objects.requireNonNull(bankTransferId);
        this.bankTransferId = bankTransferId;
        this.status = PayoutStatus.SENT;
        return new PayoutSent(id, tradeId, sellerId, netAmount, bankTransferId, now);
    }

    public PayoutCompleted complete(Instant now) {
        if (status != PayoutStatus.SENT) {
            throw new IllegalStateException("must be SENT to complete, was " + status);
        }
        this.status = PayoutStatus.COMPLETED;
        this.completedAt = now;
        return new PayoutCompleted(id, tradeId, sellerId, netAmount, now);
    }

    public PayoutFailed fail(String reason, Instant now) {
        if (status == PayoutStatus.COMPLETED) throw new IllegalStateException("already COMPLETED");
        this.status = PayoutStatus.FAILED;
        this.completedAt = now;
        return new PayoutFailed(id, tradeId, sellerId, reason, now);
    }

    public PayoutId id() { return id; }
    public TradeId tradeId() { return tradeId; }
    public UserId sellerId() { return sellerId; }
    public Money tradeAmount() { return tradeAmount; }
    public Money sellerCommission() { return sellerCommission; }
    public Money processingFee() { return processingFee; }
    public Money netAmount() { return netAmount; }
    public PayoutStatus status() { return status; }
    public String bankTransferId() { return bankTransferId; }
    public Instant createdAt() { return createdAt; }
    public Instant completedAt() { return completedAt; }
    public long version() { return version; }

    public record PayoutSent(PayoutId payoutId, TradeId tradeId, UserId sellerId, Money netAmount,
                             String bankTransferId, Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return payoutId.toString(); }
    }

    public record PayoutCompleted(PayoutId payoutId, TradeId tradeId, UserId sellerId, Money netAmount,
                                  Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return payoutId.toString(); }
    }

    public record PayoutFailed(PayoutId payoutId, TradeId tradeId, UserId sellerId, String reason,
                               Instant occurredAt) implements DomainEvent {
        @Override public String aggregateId() { return payoutId.toString(); }
    }
}
