package com.example.market.adapter.out.persistence.compensation

import com.example.market.application.port.out.CompensationLogStore
import com.example.market.application.port.out.CompensationLogStore.DuplicateBeginException
import com.example.market.application.port.out.CompensationLogStore.Entry
import com.example.market.application.port.out.CompensationLogStore.Status
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Optional

/**
 * [CompensationLogStore] 의 JPA 구현. PK 충돌 → [DuplicateBeginException] 으로 변환.
 *
 * 각 메서드는 짧은 별도 트랜잭션 ([Propagation.REQUIRES_NEW]) — 보상 트랜잭션의 메인
 * 흐름이 commit/rollback 되더라도 compensation_log 자체는 별도로 박힌다. 외부 호출이 *실제
 * 일어났는지* 의 단서가 메인 트랜잭션의 결과와 분리돼 추적 가능.
 */
@Component
class JpaCompensationLogStore(
    private val repo: CompensationLogRepository,
) : CompensationLogStore {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun begin(operation: String, businessKey: String, now: Instant) {
        val entity = CompensationLogJpaEntity(
            operation = operation,
            businessKey = businessKey,
            status = CompensationLogJpaEntity.Status.IN_PROGRESS,
            responseCode = null,
            responseMessage = null,
            externalId = null,
            startedAt = now,
            completedAt = null,
        )
        try {
            repo.saveAndFlush(entity)
        } catch (e: DataIntegrityViolationException) {
            // PK 충돌 — 같은 키가 이미 있다.
            throw DuplicateBeginException(operation, businessKey)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun complete(
        operation: String,
        businessKey: String,
        responseCode: String?,
        responseMessage: String?,
        externalId: String?,
        now: Instant,
    ) {
        val entity = repo.findByOperationAndBusinessKey(operation, businessKey)
            .orElseThrow {
                IllegalStateException(
                    "compensation_log row 가 begin 없이 complete 호출됨 op=$operation key=$businessKey",
                )
            }
        entity.status = CompensationLogJpaEntity.Status.COMPLETED
        entity.responseCode = responseCode
        entity.responseMessage = responseMessage
        entity.externalId = externalId
        entity.completedAt = now
        repo.save(entity)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun fail(
        operation: String,
        businessKey: String,
        responseCode: String?,
        responseMessage: String?,
        now: Instant,
    ) {
        val entity = repo.findByOperationAndBusinessKey(operation, businessKey)
            .orElseThrow {
                IllegalStateException(
                    "compensation_log row 가 begin 없이 fail 호출됨 op=$operation key=$businessKey",
                )
            }
        entity.status = CompensationLogJpaEntity.Status.FAILED
        entity.responseCode = responseCode
        entity.responseMessage = responseMessage
        entity.completedAt = now
        repo.save(entity)
    }

    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    override fun find(operation: String, businessKey: String): Optional<Entry> =
        repo.findByOperationAndBusinessKey(operation, businessKey).map(::toEntry)

    private fun toEntry(e: CompensationLogJpaEntity): Entry = Entry(
        e.operation!!,
        e.businessKey!!,
        Status.valueOf(e.status!!.name),
        e.responseCode,
        e.responseMessage,
        e.externalId,
        e.startedAt!!,
        e.completedAt,
    )
}
