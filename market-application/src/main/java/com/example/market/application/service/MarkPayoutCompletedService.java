package com.example.market.application.service;

import com.example.market.application.exception.PayoutNotFoundException;
import com.example.market.application.port.in.MarkPayoutCompletedUseCase;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.PayoutRepository;
import com.example.market.domain.settlement.Payout;
import com.example.market.domain.settlement.PayoutId;
import com.example.market.domain.settlement.PayoutStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarkPayoutCompletedService implements MarkPayoutCompletedUseCase {

    private final PayoutRepository payouts;
    private final EventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public void markCompleted(PayoutId payoutId) {
        Payout payout = payouts.findById(payoutId)
                .orElseThrow(() -> new PayoutNotFoundException(payoutId));
        if (payout.status() == PayoutStatus.COMPLETED) {
            log.info("payout already completed — idempotent skip {}", payoutId);
            return;
        }
        var ev = payout.complete(clock.instant());
        payouts.save(payout);
        events.publish(ev);
        log.info("payout completed {}", payoutId);
    }
}
