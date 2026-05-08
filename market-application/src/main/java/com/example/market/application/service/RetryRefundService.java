package com.example.market.application.service;

import com.example.market.application.exception.PgFailureException;
import com.example.market.application.exception.RefundNotFoundException;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.RetryRefundUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PgClient;
import com.example.market.application.port.out.RefundRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.settlement.Refund;
import com.example.market.domain.settlement.RefundId;
import com.example.market.domain.settlement.RefundStatus;
import com.example.market.domain.trading.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 운영자 admin endpoint — Refund.FAILED 상태의 환불을 PG 재호출.
 *
 * <p>Refund 자체는 새 인스턴스로 만들지 않고, 기존 Refund 의 status 가 FAILED 인 것을 *재요청*.
 * 도메인은 Refund 의 재시도 메서드가 없으므로, 새 Refund 를 만들어 처리 — 이전 Refund 는 audit log.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetryRefundService implements RetryRefundUseCase {

    /** RetryRefund 의 보상 키 — 같은 RefundId 에 대한 재시도를 구분 (원래 REFUND 와 별 row). */
    private static final String OP = "REFUND_RETRY";

    private final RefundRepository refunds;
    private final TradeRepository trades;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final CompensationGuard compensationGuard;
    private final Clock clock;

    @Override
    @Transactional
    public void retry(RefundId refundId) {
        Refund failedRefund = refunds.findById(refundId)
                .orElseThrow(() -> new RefundNotFoundException(refundId));
        if (failedRefund.status() != RefundStatus.FAILED) {
            throw new IllegalStateException("retry only allowed for FAILED, was " + failedRefund.status());
        }
        Trade trade = trades.findById(failedRefund.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(failedRefund.tradeId()));

        Instant now = clock.instant();
        Refund retry = Refund.request(trade.id(), failedRefund.buyerId(),
                failedRefund.amount(), "RETRY: " + failedRefund.reason(), now);
        refunds.save(retry);

        var outcome = compensationGuard.runOnce(OP, refundId.toString(), prev -> {
            var result = pgClient.refund(new PgClient.RefundRequest(
                    trade.pgPaymentId(), retry.amount(), retry.reason()));
            if (result.approved()) {
                return CompensationGuard.Outcome.completed(
                        result.pgRefundId(), "APPROVED", "ok", result);
            }
            return CompensationGuard.Outcome.failed(
                    "REJECTED", result.errorMessage(), result);
        });

        if (outcome.completed()) {
            var doneEv = retry.complete(outcome.externalId(), now);
            var closeEv = trade.closeAsFailedAfterRefund(now);
            refunds.save(retry);
            trades.save(trade);
            events.publishAll(doneEv, closeEv);
            log.info("refund retry succeeded refund={} pgRefundId={}", retry.id(), outcome.externalId());
        } else {
            var failEv = retry.fail(outcome.responseMessage(), now);
            refunds.save(retry);
            events.publish(failEv);
            log.error("refund retry failed again refund={} reason={}", retry.id(), outcome.responseMessage());
            throw new PgFailureException("REFUND_RETRY_REJECTED", outcome.responseMessage());
        }
    }
}
