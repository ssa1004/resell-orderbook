package com.example.market.domain.shared;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    private static final Instant FIXED = Instant.parse("2026-05-08T12:00:00Z");

    @Test
    void rejectsMachineIdOutOfRange() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machineId");
        assertThatThrownBy(() -> new SnowflakeIdGenerator(1024, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void monotonicallyIncreasingWithinSameMs() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(7, clock);

        long previous = Long.MIN_VALUE;
        for (int i = 0; i < 1000; i++) {
            long id = gen.nextId();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
    }

    @Test
    void encodesMachineIdAndTimestamp() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(42, clock);

        long id = gen.nextId();

        assertThat(SnowflakeIdGenerator.machineIdOf(id)).isEqualTo(42L);
        assertThat(SnowflakeIdGenerator.sequenceOf(id)).isEqualTo(0L);
        // 같은 ms 안에서 두 번째 호출 → sequence=1
        assertThat(SnowflakeIdGenerator.sequenceOf(gen.nextId())).isEqualTo(1L);
    }

    @Test
    void timestampOfRoundtripsBack() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, clock);

        long id = gen.nextId();

        assertThat(SnowflakeIdGenerator.timestampOf(id))
                .isEqualTo(FIXED.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void fullSequenceWaitsForNextMs() {
        // 시계가 처음 200번 호출 동안은 고정, 이후 호출부터는 +1ms 씩 진행하는 가짜 Clock.
        AdvancingClock clock = new AdvancingClock(FIXED);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, clock);

        // 4096 번 같은 ms 에서 호출 → 마지막 호출은 sequence overflow 로 다음 ms 대기.
        long firstTs = SnowflakeIdGenerator.timestampOf(gen.nextId()).toEpochMilli();
        for (int i = 1; i < 4096; i++) {
            gen.nextId();
        }
        // 다음 호출은 새로운 ms 를 강제 — Clock 의 advance() 로 1ms 진행시켜야 시뮬레이션 가능.
        clock.advance(Duration.ofMillis(1));
        long overflowed = gen.nextId();
        long overflowedTs = SnowflakeIdGenerator.timestampOf(overflowed).toEpochMilli();

        assertThat(overflowedTs).isEqualTo(firstTs + 1);
        assertThat(SnowflakeIdGenerator.sequenceOf(overflowed)).isEqualTo(0L);
    }

    @Test
    void clockBackwardWithinToleranceKeepsMonotonic() {
        AdvancingClock clock = new AdvancingClock(FIXED);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, clock);

        long first = gen.nextId();
        // 시계가 살짝 뒤로 — sequence 는 계속 증가해서 단조 증가 유지.
        clock.advance(Duration.ofMillis(-100));
        long second = gen.nextId();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void clockBackwardBeyondToleranceThrows() {
        AdvancingClock clock = new AdvancingClock(FIXED);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, clock);
        gen.nextId();

        clock.advance(Duration.ofSeconds(-30));   // 30초 역행 — 한도 초과
        assertThatThrownBy(gen::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("뒤로");
    }

    @Test
    void uniqueAcrossThreads() throws Exception {
        AdvancingClock clock = new AdvancingClock(FIXED);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, clock);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Set<Long> ids = new HashSet<>();
            int n = 8 * 500;
            var futures = IntStream.range(0, n)
                    .mapToObj(i -> pool.submit(gen::nextId))
                    .toList();
            for (Future<Long> f : futures) {
                ids.add(f.get(5, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(n);   // 충돌 없음
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentMachineIdsProduceDistinctIds() {
        Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
        SnowflakeIdGenerator g1 = new SnowflakeIdGenerator(1, clock);
        SnowflakeIdGenerator g2 = new SnowflakeIdGenerator(2, clock);

        assertThat(g1.nextId()).isNotEqualTo(g2.nextId());
        // machineId 가 달라 같은 ms / sequence 라도 ID 가 다르다 → cross-pod 충돌 없음.
        assertThat(SnowflakeIdGenerator.machineIdOf(g1.nextId())).isEqualTo(1L);
        assertThat(SnowflakeIdGenerator.machineIdOf(g2.nextId())).isEqualTo(2L);
    }

    /** mutable 한 가짜 Clock — advance() 로 임의 시간 조작 가능. */
    static final class AdvancingClock extends Clock {
        private Instant now;

        AdvancingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }
    }
}
