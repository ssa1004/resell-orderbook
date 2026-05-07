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
 * TradeMatched 이벤트 컨슈머 → PG (결제 게이트웨이) authorize 호출 → 결과에 따라
 * Trade.authorizePayment 또는 cancelOnPaymentFailure 호출.
 *
 * <p>멱등 처리: Kafka 가 메시지를 최소 한 번 이상 전달 (at-least-once) 하므로 중복 호출이
 * 가능하다 → Trade 의 현재 상태가 CREATED 인 경우에만 진행하고 나머지는 그냥 건너뛴다.
 * PG 측 idempotency-key (PG 가 같은 결제 요청을 두 번 받아도 한 번만 결제하도록 식별하는
 * 키) 로는 TradeId 를 그대로 사용 — 같은 거래에 결제는 한 번만 발생.</p>
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
                trade.id().toString(),                 // PG 측 멱등성 키 = tradeId (같은 거래에 결제는 한 번만)
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
