package com.example.market.adapter.out.inspection

import com.example.market.application.port.out.InspectionSlotLockPort
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 검수 슬롯 잠금 어댑터 — PostgreSQL 의 advisory_xact_lock (트랜잭션 단위로 임의의 정수 키에
 * 거는 응용 락) 으로 (검수센터, 슬롯시작) 조합에 대한 동시 시도를 한 줄로 줄세운다. 정원 초과
 * (over-booking) 방지에 사용.
 *
 * H2 에는 이 함수가 없으므로 native query 가 실패할 수 있다 — application.yml 에서 PostgreSQL
 * 호환 모드 + ALIAS 등록을 사용하거나, dev 환경에서는 잠금 자체를 끈다 (워커 한 대라 경쟁 없음).
 */
@Component
class JpaInspectionSlotLockAdapter : InspectionSlotLockPort {

    @PersistenceContext
    private lateinit var em: EntityManager

    override fun acquireSlotLock(centerId: InspectionCenterId, slotStart: Instant) {
        val key = lockKey(centerId, slotStart)
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)")
            .setParameter("k", key)
            .singleResult
    }

    companion object {
        /**
         * (centerId UUID, slotStart epoch-seconds) 를 64bit 정수 한 개로 압축한다 — advisory_xact_lock
         * 의 인자 타입이 long 이므로 슬롯 식별자를 long 한 개에 담아야 한다.
         *
         * **왜 단순 해시로 안 되나**: `Objects.hash(...)` 는 int (32bit) 만 반환한다.
         * advisory lock 키 공간은 64bit 인데 32bit 만 쓰면 표현 가능한 슬롯 수가 절반 이하로 줄고,
         * 서로 무관한 두 (center, slot) 쌍이 우연히 같은 정수에 떨어질 확률이 늘어난다. 다른 두
         * 슬롯이 같은 락 키 위에서 차례를 기다리는 false sharing 이 생기면 처리량이 떨어진다.
         *
         * **해법**: 세 입력 (UUID 의 상위 64bit, 하위 64bit, epoch-seconds) 을 64bit avalanche
         * mixer 에 순차로 흘려 누적한다. avalanche mixer 는 입력 비트 한 개만 바뀌어도 출력 절반
         * 정도가 뒤집히는 함수 — 입력이 구조적으로 비슷해도 (예: 연속 UUID, 인접 시각) 출력은
         * 균일하게 흩어진다. 여기 쓰는 함수는 Stafford variant 13 (Mix13), JDK 의
         * [java.util.SplittableRandom] 이 내부적으로 쓰는 finalizer 와 같은 식이다.
         *
         * **왜 매 입력마다 한 번씩 흘리나**: mixer 한 번만 거쳐도 64bit 안에서 일대일 (bijection)
         * 로 흩어지지만, 세 입력을 한 번에 XOR 로 묶어 한 번만 mix 하면 입력이 구조적일 때 XOR 단계
         * 의 비트 상관이 남는다. 입력 → mix → 다음 입력 XOR → 다시 mix 의 패턴으로 매 단계마다
         * 누적 결과를 한 번씩 흔들어준다. 같은 (center, slot) 은 항상 같은 키 → 같은 슬롯 동시
         * 시도만 같은 락 위에서 줄을 선다.
         */
        @JvmStatic
        fun lockKey(centerId: InspectionCenterId, slotStart: Instant): Long {
            val id = centerId.value
            var h = mix(id.mostSignificantBits)
            h = mix(h xor id.leastSignificantBits)
            h = mix(h xor slotStart.epochSecond)
            return h
        }

        /** Stafford 13 — 64bit avalanche mixer. */
        private fun mix(input: Long): Long {
            var z = input
            z = (z xor (z ushr 30)) * -4658895280553007687L // 0xbf58476d1ce4e5b7
            z = (z xor (z ushr 27)) * -6298335757008793789L // 0x94d049bb133111eb
            return z xor (z ushr 31)
        }
    }
}
