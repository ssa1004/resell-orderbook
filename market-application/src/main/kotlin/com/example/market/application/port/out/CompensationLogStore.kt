package com.example.market.application.port.out

import java.time.Instant
import java.util.Optional

/**
 * SAGA 보상 트랜잭션 (refund / payout 재시도 등) 의 명시 멱등성 로그 (ADR-0023).
 *
 * 외부 호출 직전 [begin] 으로 자리를 점유하고, 결과를 [complete] / [fail]
 * 로 박는다. 재시도 시 [find] 로 기존 결과가 있는지 먼저 확인 — 있으면 외부 호출 없이
 * 캐시된 결과를 그대로 반환 (PG/은행이 두 번 호출되지 않음).
 *
 * ### UNIQUE 보장
 *
 * `(operation, businessKey)` 가 PK — 같은 보상 1건당 row 1개. 동시 두 thread 가 같은
 * 메시지를 처리해 동시에 [begin] 을 호출하면 DB 가 두 번째 INSERT 를 거절해
 * [DuplicateBeginException] 발생. 호출자는 [find] 로 다시 조회해 *진행 중* 인지
 * *완료된 결과* 인지 분기.
 */
interface CompensationLogStore {

    /**
     * 보상 트랜잭션 시작 — IN_PROGRESS row 를 INSERT. 같은 키가 이미 있으면
     * [DuplicateBeginException].
     */
    fun begin(operation: String, businessKey: String, now: Instant)

    /**
     * 외부 호출 성공 결과 기록.
     *
     * @param externalId pgRefundId / bankTransferId 등 외부 시스템 식별자 (null 가능)
     */
    fun complete(
        operation: String,
        businessKey: String,
        responseCode: String,
        responseMessage: String,
        externalId: String?,
        now: Instant,
    )

    /** 외부 호출 실패 결과 기록 — 재시도 가능. */
    fun fail(
        operation: String,
        businessKey: String,
        responseCode: String,
        responseMessage: String,
        now: Instant,
    )

    fun find(operation: String, businessKey: String): Optional<Entry>

    /** PK 충돌 — 같은 키로 동시에 begin 시도. 호출자가 find 로 재조회. */
    class DuplicateBeginException(operation: String, businessKey: String) :
        RuntimeException("compensation already in progress: op=$operation key=$businessKey")

    enum class Status { IN_PROGRESS, COMPLETED, FAILED }

    /** 로그 엔트리 — 외부 호출의 캐시된 결과. */
    @JvmRecord
    data class Entry(
        val operation: String,
        val businessKey: String,
        val status: Status,
        val responseCode: String?,
        val responseMessage: String?,
        val externalId: String?,
        val startedAt: Instant,
        val completedAt: Instant?,
    ) {
        fun isCompleted(): Boolean = status == Status.COMPLETED
        fun isFailed(): Boolean = status == Status.FAILED
        fun isInProgress(): Boolean = status == Status.IN_PROGRESS
    }
}
