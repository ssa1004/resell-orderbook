package com.example.market.adapter.out.bulkhead;

import com.example.market.application.port.out.BankTransferClient;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link BankTransferClient} 의 격리 풀 데코레이터. PG 와 별도 풀 — 은행 송금 지연이 PG 호출
 * 풀까지 영향 주지 않게 외부 의존마다 풀을 분리.
 *
 * <p>fallback 은 도메인 거절 결과 (rejected) 로 변환. {@code SettleTradeService} 가 이미 거절을
 * Payout.fail 로 받아주는 흐름이라 일관된 처리.</p>
 */
@Slf4j
public class BulkheadedBankTransferClient implements BankTransferClient {

    private final BankTransferClient delegate;
    private final ExternalCallBulkhead bulkhead;

    public BulkheadedBankTransferClient(BankTransferClient delegate, ExternalCallBulkhead bulkhead) {
        this.delegate = delegate;
        this.bulkhead = bulkhead;
    }

    @Override
    public SendResult send(SendRequest req) {
        try {
            return bulkhead.execute(() -> delegate.send(req));
        } catch (ExternalCallBulkhead.BulkheadCapacityExceededException e) {
            log.warn("[bank] bulkhead 포화 — send 거절 fallback (pool={})", e.poolName());
            return SendResult.rejected("BULKHEAD_FULL: 송금 시스템 부하");
        } catch (ExternalCallBulkhead.BulkheadAwaitTimeoutException e) {
            log.warn("[bank] bulkhead await timeout — send 거절 fallback (pool={})", e.poolName());
            return SendResult.rejected("BULKHEAD_TIMEOUT: 송금 응답 지연");
        }
    }
}
