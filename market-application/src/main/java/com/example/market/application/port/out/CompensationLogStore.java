package com.example.market.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * SAGA 보상 트랜잭션 (refund / payout 재시도 등) 의 명시 멱등성 로그 (ADR-0023).
 *
 * <p>외부 호출 직전 {@link #begin} 으로 자리를 점유하고, 결과를 {@link #complete} / {@link #fail}
 * 로 박는다. 재시도 시 {@link #find} 로 기존 결과가 있는지 먼저 확인 — 있으면 외부 호출 없이
 * 캐시된 결과를 그대로 반환 (PG/은행이 두 번 호출되지 않음).</p>
 *
 * <h3>UNIQUE 보장</h3>
 *
 * <p>{@code (operation, businessKey)} 가 PK — 같은 보상 1건당 row 1개. 동시 두 thread 가 같은
 * 메시지를 처리해 동시에 {@link #begin} 을 호출하면 DB 가 두 번째 INSERT 를 거절해
 * {@link DuplicateBeginException} 발생. 호출자는 {@link #find} 로 다시 조회해 *진행 중* 인지
 * *완료된 결과* 인지 분기.</p>
 */
public interface CompensationLogStore {

    /**
     * 보상 트랜잭션 시작 — IN_PROGRESS row 를 INSERT. 같은 키가 이미 있으면
     * {@link DuplicateBeginException}.
     */
    void begin(String operation, String businessKey, Instant now);

    /**
     * 외부 호출 성공 결과 기록.
     *
     * @param externalId pgRefundId / bankTransferId 등 외부 시스템 식별자 (null 가능)
     */
    void complete(String operation, String businessKey,
                  String responseCode, String responseMessage, String externalId,
                  Instant now);

    /** 외부 호출 실패 결과 기록 — 재시도 가능. */
    void fail(String operation, String businessKey,
              String responseCode, String responseMessage,
              Instant now);

    Optional<Entry> find(String operation, String businessKey);

    /** PK 충돌 — 같은 키로 동시에 begin 시도. 호출자가 find 로 재조회. */
    class DuplicateBeginException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DuplicateBeginException(String operation, String businessKey) {
            super("compensation already in progress: op=" + operation + " key=" + businessKey);
        }
    }

    enum Status { IN_PROGRESS, COMPLETED, FAILED }

    /** 로그 엔트리 — 외부 호출의 캐시된 결과. */
    record Entry(
            String operation,
            String businessKey,
            Status status,
            String responseCode,
            String responseMessage,
            String externalId,
            Instant startedAt,
            Instant completedAt
    ) {
        public boolean isCompleted() { return status == Status.COMPLETED; }
        public boolean isFailed()    { return status == Status.FAILED; }
        public boolean isInProgress() { return status == Status.IN_PROGRESS; }
    }
}
