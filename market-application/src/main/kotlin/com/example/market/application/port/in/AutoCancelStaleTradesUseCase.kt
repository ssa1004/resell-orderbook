package com.example.market.application.port.`in`

import java.time.Duration

/**
 * Spring Batch — TTL (예: 15분) 지난 CREATED 거래 자동 cancelOnPaymentFailure.
 *
 * 매칭 후 PG authorize 가 늦어지면 호가창의 ASK/BID 가 markMatched 된 채로 묶임 →
 * 자동 cancel 로 정리. ASK/BID 자체는 이미 MATCHED 상태이므로 재활성화는 안 함
 * (한번 매칭된 호가는 사용자가 새로 등록).
 */
interface AutoCancelStaleTradesUseCase {
    fun cancelStale(ttl: Duration, batchSize: Int): Int
}
