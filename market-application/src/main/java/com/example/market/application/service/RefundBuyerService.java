package com.example.market.application.service;

import com.example.market.application.exception.PgFailureException;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.RefundBuyerUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PgClient;
import com.example.market.application.port.out.RefundRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.settlement.Refund;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeId;
import com.example.market.domain.trading.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * InspectionFailed 컨슈머 — Trade.startRefunding → PG.refund() → Refund.complete →
 * Trade.closeAsFailedAfterRefund.
 *
 * <p>idempotent: trade 가 이미 REFUNDING/FAILED 면 새 Refund 생성하지 않음.</p>
 *
 * <p>PG.refund 실패 시 Refund.fail() — 운영자가 RetryRefundUseCase 로 재시도.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundBuyerService implements RefundBuyerUseCase {

    private final TradeRepository trades;
    private final RefundRepository refunds;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Refund refund(TradeId tradeId) {
        Trade trade = trades.findById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));

        // idempotent — 이미 REFUNDING/FAILED 라면 기존 Refund 반환
        var existing = refunds.findByTradeId(tradeId);
        if (existing.isPresent()) {
            log.info("refund idempotent — trade {} already has refund {}", tradeId, existing.get().id());
            return existing.get();
        }
        if (trade.status() != TradeStatus.INSPECTION_FAILED) {
            throw new IllegalStateException(
                    "refund requires INSPECTION_FAILED, was " + trade.status());
        }

        Instant now = clock.instant();
        var startEv = trade.startRefunding(now);
        Refund refund = Refund.request(trade.id(), trade.buyerId(),
                trade.feeSnapshot().buyerCharge(),  // 검수비/배송비 포함 전액
                trade.inspectionFailReason(), now);
        refunds.save(refund);
        trades.save(trade);
        events.publish(startEv);

        // PG 환불 호출
        var result = pgClient.refund(new PgClient.RefundRequest(
                trade.pgPaymentId(), refund.amount(), refund.reason()));

        if (result.approved()) {
            var doneEv = refund.complete(result.pgRefundId(), now);
            var closeEv = trade.closeAsFailedAfterRefund(now);
            refunds.save(refund);
            trades.save(trade);
            events.publishAll(doneEv, closeEv);
            log.info("refund complete trade={} pgRefundId={}", trade.id(), result.pgRefundId());
        } else {
            var failEv = refund.fail(result.errorMessage(), now);
            refunds.save(refund);
            events.publish(failEv);
            log.warn("refund failed trade={} reason={}", trade.id(), result.errorMessage());
            // PG 실패 — Trade 는 REFUNDING 에 머무름. 운영자 RetryRefundUseCase 로 처리
            throw new PgFailureException("REFUND_REJECTED", result.errorMessage());
        }
        return refund;
    }
}
