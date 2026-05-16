package com.example.market.application.service

import com.example.market.application.port.`in`.AutoCancelStaleTradesUseCase
import com.example.market.application.port.out.EventPublisher
import com.example.market.application.port.out.TradeRepository
import com.example.market.domain.trading.TradeStatus
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * TTL (예: 15분) 지난 CREATED 거래 자동 cancelOnPaymentFailure.
 * 매칭 후 PG authorize 가 늦어진 거래를 정리.
 */
@Service
open class AutoCancelStaleTradesService(
    private val trades: TradeRepository,
    private val events: EventPublisher,
    private val clock: Clock,
) : AutoCancelStaleTradesUseCase {

    @Transactional
    override fun cancelStale(ttl: Duration, batchSize: Int): Int {
        require(batchSize > 0) { "batchSize must be positive: $batchSize" }
        val now = clock.instant()
        val cutoff = now.minus(ttl)
        // batchSize 만큼만 fetch — 이전엔 repository 가 200 hard-coded 였어서 caller 의 hint
        // 가 무시됐다 (500/1000 호출해도 200 row 만 처리). 이제 호출자의 처리 단위와
        // fetch 단위가 일치.
        val stale = trades.findStaleCreated(cutoff, batchSize)
        if (stale.isEmpty()) return 0

        var cancelled = 0
        for (trade in stale) {
            if (trade.status != TradeStatus.CREATED) continue // safety
            val ev = trade.cancelOnPaymentFailure("PAYMENT_TTL_EXCEEDED", now)
            trades.save(trade)
            events.publish(ev)
            cancelled++
        }
        log.info(
            "auto-cancelled {} stale trades (TTL={}min, batchSize={})",
            cancelled, ttl.toMinutes(), batchSize,
        )
        return cancelled
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutoCancelStaleTradesService::class.java)
    }
}
