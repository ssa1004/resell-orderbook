package com.example.market.adapter.out.inspection;

import com.example.market.application.port.out.InspectionSlotLockPort;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * 검수 슬롯 잠금 어댑터 — PostgreSQL 의 advisory_xact_lock (트랜잭션 단위로 임의의 정수 키에
 * 거는 응용 락) 으로 (검수센터, 슬롯시작) 조합에 대한 동시 시도를 한 줄로 줄세운다. 정원 초과
 * (over-booking) 방지에 사용.
 *
 * <p>H2 에는 이 함수가 없으므로 native query 가 실패할 수 있다 — application.yml 에서 PostgreSQL
 * 호환 모드 + ALIAS 등록을 사용하거나, dev 환경에서는 잠금 자체를 끈다 (워커 한 대라 경쟁 없음).</p>
 */
@Component
public class JpaInspectionSlotLockAdapter implements InspectionSlotLockPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    public void acquireSlotLock(InspectionCenterId centerId, Instant slotStart) {
        // (centerId, 슬롯 시작 시각의 epoch 초) 를 합쳐 정수 키 한 개를 만든다. 같은 슬롯이면
        // 항상 같은 키 → PG advisory_xact_lock 이 받아 같은 키끼리만 직렬화한다.
        long key = Objects.hash(centerId.value(), slotStart.getEpochSecond());
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
                .setParameter("k", key)
                .getSingleResult();
    }
}
