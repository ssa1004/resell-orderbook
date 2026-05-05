package com.example.market.application.service;

import com.example.market.application.command.AuthorizePaymentCommand;
import com.example.market.application.exception.TradeNotFoundException;
import com.example.market.application.port.in.AuthorizePaymentUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PgClient;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * TradeMatched 이벤트 컨슈머 → PG.authorize() → Trade.authorizePayment / cancelOnPaymentFailure.
 *
 * <p>Idempotency: 컨슈머 at-least-once 보장으로 중복 호출 가능 → Trade 상태 체크 (CREATED 만 진행).
 * PG 자체 idempotency-key 는 TradeId 사용 — 같은 거래에 PG 결제는 한 번만.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizePaymentService implements AuthorizePaymentUseCase {

    private final TradeRepository trades;
    private final PgClient pgClient;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public Trade authorize(AuthorizePaymentCommand cmd) {
        Trade trade = trades.findById(cmd.tradeId())
                .orElseThrow(() -> new TradeNotFoundException(cmd.tradeId()));

        if (trade.status() != TradeStatus.CREATED) {
            log.info("authorize idempotent skip — trade {} already in {}", trade.id(), trade.status());
            return trade;
        }

        Instant now = clock.instant();
        var req = new PgClient.AuthorizeRequest(
                trade.id().toString(),                 // idempotency-key = tradeId
                trade.feeSnapshot().buyerCharge(),
                trade.id().toString(),
                trade.buyerId().value());
        var result = pgClient.authorize(req);

        if (result.approved()) {
            var ev = trade.authorizePayment(result.pgPaymentId(), now);
            trades.save(trade);
            events.publish(ev);
            log.info("payment authorized trade={} pgPaymentId={}", trade.id(), result.pgPaymentId());
        } else {
            var ev = trade.cancelOnPaymentFailure(
                    result.errorCode() + ":" + result.errorMessage(), now);
            trades.save(trade);
            events.publish(ev);
            log.warn("payment rejected trade={} reason={}", trade.id(), result.errorMessage());
        }
        return trade;
    }
}
