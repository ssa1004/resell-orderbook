package com.example.market.application.service;

import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.SettleTradeUseCase;
import com.example.market.application.port.out.BankTransferClient;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PayoutRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.settlement.Payout;
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
 * TradeCompleted 컨슈머 → Payout.schedule(snapshot) → bankTransfer.send → Payout.send.
 *
 * <p>Idempotency 이중 보호 (ADR-0023):
 * <ol>
 *   <li>1차 — 같은 Trade 에 Payout 이 이미 있으면 기존 반환 (DB UNIQUE).</li>
 *   <li>2차 — {@link CompensationGuard} 로 은행 송금 호출이 *정확히 한 번* 일어나게. 응답 유실
 *       시나리오에서 같은 trade 에 송금이 두 번 일어나지 않게 한다.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettleTradeService implements SettleTradeUseCase {

    private static final String OP = "SETTLE_PAYOUT";

    private final TradeRepository trades;
    private final PayoutRepository payouts;
    private final BankTransferClient bank;
    private final EventPublisher events;
    private final CompensationGuard compensationGuard;
    private final Clock clock;

    @Override
    @Transactional
    public Payout settle(TradeId tradeId) {
        var existing = payouts.findByTradeId(tradeId);
        if (existing.isPresent()) {
            log.info("settle idempotent — trade {} already has payout {}", tradeId, existing.get().id());
            return existing.get();
        }
        Trade trade = trades.findById(tradeId)
                .orElseThrow(() -> new TradeNotFoundException(tradeId));
        if (trade.status() != TradeStatus.COMPLETED) {
            throw new IllegalStateException("settle requires COMPLETED, was " + trade.status());
        }

        Instant now = clock.instant();
        Payout payout = Payout.schedule(trade.id(), trade.sellerId(), trade.feeSnapshot(), now);
        payouts.save(payout);

        var outcome = compensationGuard.runOnce(OP, trade.id().toString(), prev -> {
            var sendResult = bank.send(new BankTransferClient.SendRequest(
                    payout.id().toString(), trade.sellerId(), payout.netAmount(),
                    "RESELL settlement " + trade.id()));
            if (sendResult.accepted()) {
                return CompensationGuard.Outcome.completed(
                        sendResult.bankTransferId(), "ACCEPTED", "ok", sendResult);
            }
            return CompensationGuard.Outcome.failed(
                    "REJECTED", sendResult.errorMessage(), sendResult);
        });

        if (outcome.completed()) {
            var ev = payout.send(outcome.externalId(), now);
            payouts.save(payout);
            events.publish(ev);
            log.info("payout sent trade={} payout={} amount={}",
                    trade.id(), payout.id(),
                    SensitiveLogging.maskAmount(payout.netAmount().amount()));
        } else {
            var failEv = payout.fail(outcome.responseMessage(), now);
            payouts.save(payout);
            events.publish(failEv);
            log.warn("payout send failed trade={} reason={}", trade.id(), outcome.responseMessage());
        }
        return payout;
    }
}
