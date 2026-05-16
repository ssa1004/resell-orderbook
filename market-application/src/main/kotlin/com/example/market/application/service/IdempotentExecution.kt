package com.example.market.application.service

import com.example.market.application.port.out.IdempotencyKeyStore
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Idempotency-Key 점유 + 트랜잭션 rollback 시 자동 release 까지 묶어주는 helper.
 *
 * **왜 필요한가**: [IdempotencyKeyStore.acquireOrThrow] 만 단독으로 호출하면 점유
 * 기록 (Redis 의 SETNX) 이 즉시 박힌다. 그 뒤 같은 트랜잭션 안에서 도메인 검증이 실패하거나
 * 외부 호출 예외로 rollback 이 되어도 Redis 점유는 남아 있다. 같은 키로 정당한 재시도가
 * 들어와도 24시간 TTL 이 만료될 때까지 [IdempotencyKeyStore.DuplicateRequestException]
 * 만 떨어진다 — 사용자가 자기 자신의 재시도에 막히는 사실상의 DoS.
 *
 * **해결**: 점유 직후 트랜잭션의 완료 후크 (Spring 의 [TransactionSynchronization]
 * 콜백 — 트랜잭션이 commit/rollback 된 직후 한 번 호출됨) 를 등록해서, rollback 으로 끝났을
 * 때만 [IdempotencyKeyStore.release] 를 부른다. commit 으로 끝난 정상 흐름에서는
 * 점유를 그대로 둬서 진짜 중복 요청 방어는 유지.
 *
 * **호출 규약**: 반드시 `@Transactional` 메서드 안에서 호출. 활성 트랜잭션이 없으면
 * 후크가 등록되지 않아 단순 acquire 와 같다 (이 경우 실패 시 release 책임은 호출자에게).
 */
@Component
class IdempotentExecution(
    private val store: IdempotencyKeyStore,
) {

    /**
     * 키 점유 + rollback 시 자동 release.
     *
     * @throws IdempotencyKeyStore.DuplicateRequestException 이미 같은 키로 진행 중인 요청이 있을 때
     */
    fun acquireAndReleaseOnRollback(key: String) {
        store.acquireOrThrow(key)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        store.release(key)
                    }
                }
            })
        }
    }
}
