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
 * <p>Idempotency: 같은 Trade 에 Payout 이 이미 있으면 기존 반환.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettleTradeService implements SettleTradeUseCase {

    private final TradeRepository trades;
    private final PayoutRepository payouts;
    private final BankTransferClient bank;
    private final EventPublisher events;
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

        var sendResult = bank.send(new BankTransferClient.SendRequest(
                payout.id().toString(), trade.sellerId(), payout.netAmount(),
                "RESELL settlement " + trade.id()));

        if (sendResult.accepted()) {
            var ev = payout.send(sendResult.bankTransferId(), now);
            payouts.save(payout);
            events.publish(ev);
            log.info("payout sent trade={} payout={} amount={}",
                    trade.id(), payout.id(), payout.netAmount());
        } else {
            var failEv = payout.fail(sendResult.errorMessage(), now);
            payouts.save(payout);
            events.publish(failEv);
            log.warn("payout send failed trade={} reason={}", trade.id(), sendResult.errorMessage());
        }
        return payout;
    }
}
