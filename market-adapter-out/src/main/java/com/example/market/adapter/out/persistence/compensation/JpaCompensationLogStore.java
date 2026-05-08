package com.example.market.adapter.out.persistence.compensation;

import com.example.market.application.port.out.CompensationLogStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * {@link CompensationLogStore} 의 JPA 구현. PK 충돌 → {@link CompensationLogStore.DuplicateBeginException}
 * 으로 변환.
 *
 * <p>각 메서드는 짧은 별도 트랜잭션 ({@link Propagation#REQUIRES_NEW}) — 보상 트랜잭션의 메인
 * 흐름이 commit/rollback 되더라도 compensation_log 자체는 별도로 박힌다. 외부 호출이 *실제
 * 일어났는지* 의 단서가 메인 트랜잭션의 결과와 분리돼 추적 가능.</p>
 */
@Component
@RequiredArgsConstructor
public class JpaCompensationLogStore implements CompensationLogStore {

    private static final String OP_NAME = "compensation_log";

    private final CompensationLogRepository repo;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(String operation, String businessKey, Instant now) {
        var entity = new CompensationLogJpaEntity(
                operation, businessKey,
                CompensationLogJpaEntity.Status.IN_PROGRESS,
                null, null, null,
                now, null);
        try {
            repo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // PK 충돌 — 같은 키가 이미 있다.
            throw new DuplicateBeginException(operation, businessKey);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String operation, String businessKey,
                         String responseCode, String responseMessage, String externalId,
                         Instant now) {
        var entity = repo.findByOperationAndBusinessKey(operation, businessKey)
                .orElseThrow(() -> new IllegalStateException(
                        "compensation_log row 가 begin 없이 complete 호출됨 op=" + operation
                                + " key=" + businessKey));
        entity.setStatus(CompensationLogJpaEntity.Status.COMPLETED);
        entity.setResponseCode(responseCode);
        entity.setResponseMessage(responseMessage);
        entity.setExternalId(externalId);
        entity.setCompletedAt(now);
        repo.save(entity);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String operation, String businessKey,
                     String responseCode, String responseMessage,
                     Instant now) {
        var entity = repo.findByOperationAndBusinessKey(operation, businessKey)
                .orElseThrow(() -> new IllegalStateException(
                        "compensation_log row 가 begin 없이 fail 호출됨 op=" + operation
                                + " key=" + businessKey));
        entity.setStatus(CompensationLogJpaEntity.Status.FAILED);
        entity.setResponseCode(responseCode);
        entity.setResponseMessage(responseMessage);
        entity.setCompletedAt(now);
        repo.save(entity);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Optional<Entry> find(String operation, String businessKey) {
        return repo.findByOperationAndBusinessKey(operation, businessKey)
                .map(JpaCompensationLogStore::toEntry);
    }

    private static Entry toEntry(CompensationLogJpaEntity e) {
        return new Entry(
                e.getOperation(),
                e.getBusinessKey(),
                Status.valueOf(e.getStatus().name()),
                e.getResponseCode(),
                e.getResponseMessage(),
                e.getExternalId(),
                e.getStartedAt(),
                e.getCompletedAt()
        );
    }
}
