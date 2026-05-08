package com.example.market.adapter.out.inspection;

import com.example.market.application.port.out.InspectionSlotLockPort;
import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

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
        long key = lockKey(centerId, slotStart);
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
                .setParameter("k", key)
                .getSingleResult();
    }

    /**
     * (centerId UUID, slotStart epoch-seconds) 를 64bit 정수 한 개로 압축한다.
     *
     * <p>{@code Objects.hash(...)} 는 int 만 반환해 64bit 키 공간의 절반 (sign extension) 만
     * 쓰게 되고, 다른 (center, slot) 쌍이 같은 정수로 떨어지는 false serialization (서로 무관한
     * 두 슬롯이 같은 키 위에서 줄을 서버리는 현상) 위험이 늘어난다.</p>
     *
     * <p>해법: 세 입력 (UUID 두 절반 + epoch-seconds) 을 64bit mixer (Stafford variant 13 — JDK
     * SplittableRandom 의 finalizer 와 동일) 에 순차적으로 흘려 누적한다. mixer 자체가 64bit
     * 전단사 (bijection) 라 단일 호출만으로도 잘 흩어지지만, 입력이 구조적이면 (순차 UUID +
     * 인접 시각) XOR 단계의 비트 상관관계가 남을 수 있어 매 입력마다 한 번씩 mix → XOR 누적 →
     * 다시 mix 의 패턴을 쓴다. 같은 (center, slot) 면 항상 같은 키 → 같은 슬롯끼리만
     * 줄세워진다.</p>
     */
    static long lockKey(InspectionCenterId centerId, Instant slotStart) {
        UUID id = centerId.value();
        long h = mix(id.getMostSignificantBits());
        h = mix(h ^ id.getLeastSignificantBits());
        h = mix(h ^ slotStart.getEpochSecond());
        return h;
    }

    /** Stafford 13 — 64bit avalanche mixer. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b7L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
