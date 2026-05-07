package com.example.market.adapter.out.inspection;

import com.example.market.application.port.out.InspectionSlotLockPort;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Postgres advisory_xact_lock 으로 한 (center × slot) 단위 직렬화.
 *
 * <p>H2 에서는 이 함수가 없으므로 native query 가 실패할 수 있음 — application.yml 에서
 * COMPATIBILITY_MODE=PostgreSQL + ALIAS 등록을 사용하거나, dev 에서는 lock 비활성 (단일 워커).</p>
 */
@Component
public class JpaInspectionSlotLockAdapter implements InspectionSlotLockPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void acquireSlotLock(InspectionCenterId centerId, Instant slotStart) {
        // (center.hashCode XOR slot.epochSecond) — 64-bit 키. PG advisory_xact_lock 가 받음.
        long key = Objects.hash(centerId.value(), slotStart.getEpochSecond());
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
                .setParameter("k", key)
                .getSingleResult();
    }
}
