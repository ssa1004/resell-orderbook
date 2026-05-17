package com.example.market.adapter.out.bulkhead

import com.example.market.application.port.out.BankTransferClient
import org.slf4j.LoggerFactory

/**
 * [BankTransferClient] 의 격리 풀 데코레이터. PG 와 별도 풀 — 은행 송금 지연이 PG 호출
 * 풀까지 영향 주지 않게 외부 의존마다 풀을 분리.
 *
 * fallback 은 도메인 거절 결과 (rejected) 로 변환. `SettleTradeService` 가 이미 거절을
 * Payout.fail 로 받아주는 흐름이라 일관된 처리.
 */
class BulkheadedBankTransferClient(
    private val delegate: BankTransferClient,
    private val bulkhead: ExternalCallBulkhead,
) : BankTransferClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(req: BankTransferClient.SendRequest): BankTransferClient.SendResult {
        return try {
            bulkhead.execute { delegate.send(req) }
        } catch (e: ExternalCallBulkhead.BulkheadCapacityExceededException) {
            log.warn("[bank] bulkhead 포화 — send 거절 fallback (pool={})", e.poolName)
            BankTransferClient.SendResult.rejected("BULKHEAD_FULL: 송금 시스템 부하")
        } catch (e: ExternalCallBulkhead.BulkheadAwaitTimeoutException) {
            log.warn("[bank] bulkhead await timeout — send 거절 fallback (pool={})", e.poolName)
            BankTransferClient.SendResult.rejected("BULKHEAD_TIMEOUT: 송금 응답 지연")
        }
    }
}
