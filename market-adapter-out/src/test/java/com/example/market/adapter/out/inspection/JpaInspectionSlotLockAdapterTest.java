package com.example.market.adapter.out.inspection;

import com.example.market.domain.inspection.scheduling.InspectionCenterId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 슬롯 락 키 도출이 64bit 키 공간을 얼마나 잘 활용하는지 검증.
 *
 * <p>{@code Objects.hash} 는 int 만 반환하므로 키 공간이 2^32 로 좁아진다. UUID 의 두 long
 * 절반과 epoch-seconds 를 그대로 XOR 하면 이론상 64bit 전체를 쓰는 셈이라, 무관한 (center, slot)
 * 쌍이 같은 키로 떨어져 false serialization (서로 무관한 슬롯이 한 줄에 줄 서버리는 현상) 을
 * 일으킬 가능성이 줄어든다.</p>
 */
class JpaInspectionSlotLockAdapterTest {

    @Test
    void sameCenterAndSlot_yieldSameKey() {
        InspectionCenterId center = new InspectionCenterId(
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Instant slot = Instant.parse("2026-05-04T14:00:00Z");

        long a = JpaInspectionSlotLockAdapter.lockKey(center, slot);
        long b = JpaInspectionSlotLockAdapter.lockKey(center, slot);

        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentSlots_yieldDifferentKeys_forSameCenter() {
        InspectionCenterId center = new InspectionCenterId(
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        long t1 = JpaInspectionSlotLockAdapter.lockKey(center, Instant.parse("2026-05-04T14:00:00Z"));
        long t2 = JpaInspectionSlotLockAdapter.lockKey(center, Instant.parse("2026-05-04T15:00:00Z"));

        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void wideKeyDistribution_overManyCentersAndSlots() {
        // 200 센터 × 200 슬롯 = 40,000 키. 충돌이 거의 없어야 정상 (XOR 만으로는 완벽한 해시는
        // 아니라 약간의 충돌은 가능하지만 압도적 다수가 unique 여야 함).
        Set<Long> keys = new HashSet<>();
        Instant base = Instant.parse("2026-05-04T00:00:00Z");
        for (int c = 0; c < 200; c++) {
            UUID centerUuid = new UUID(c, c * 31L + 1);
            InspectionCenterId centerId = new InspectionCenterId(centerUuid);
            for (int s = 0; s < 200; s++) {
                keys.add(JpaInspectionSlotLockAdapter.lockKey(
                        centerId, base.plusSeconds(s * 3600L)));
            }
        }
        // 충돌이 있어도 압도적 다수가 unique 여야 함.
        assertThat(keys.size()).isGreaterThanOrEqualTo(39_500);
    }
}
