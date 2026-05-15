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
        // 같은 ms 에서 4096개 (sequence 한도) 를 다 쓰면 4097번째 호출이 waitNextMillis 로
        // 다음 ms 를 기다린다. TickOnReadClock 은 그 busy-wait 동안 millis() 가 읽힐 때마다
        // 1ms 씩 진행하므로 spin 이 자연스럽게 풀린다.
        TickOnReadClock clock = new TickOnReadClock(FIXED);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, clock);

        long firstTs = SnowflakeIdGenerator.timestampOf(gen.nextId()).toEpochMilli();
        for (int i = 1; i < 4096; i++) {       // 같은 ms 에서 sequence 0..4095 소진
            gen.nextId();
        }

        // 4097번째 — sequence overflow → waitNextMillis 진입. 여기서 clock 이 진행해야 한다.
        clock.startTicking();
        long overflowed = gen.nextId();
        long overflowedTs = SnowflakeIdGenerator.timestampOf(overflowed).toEpochMilli();

        assertThat(overflowedTs).isEqualTo(firstTs + 1);
        assertThat(SnowflakeIdGenerator.sequenceOf(overflowed)).isEqualTo(0L);
    }

    @Test
    void fullSequenceWithFrozenClock_failsFastInsteadOfHanging() {
        // 진행하지 않는 Clock — sequence overflow 후 waitNextMillis 가 무한 spin 하면 CI 가
        // 영원히 멈춘다. spin 상한을 둬 빠르게 예외로 떨어지는지 검증.
        Clock frozen = Clock.fixed(FIXED, ZoneOffset.UTC);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(0, frozen);

        for (int i = 0; i < 4095; i++) {       // sequence 0..4094 소진
            gen.nextId();
        }
        // 4096번째 호출에서 sequence 가 4095 → overflow 직전. 4097번째가 waitNextMillis 진입.
        gen.nextId();                          // sequence = 4095

        assertThatThrownBy(gen::nextId)        // overflow → waitNextMillis → 고정 clock → 상한 초과
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("진행하지 않");
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

    /**
     * startTicking() 전에는 고정. startTicking() 후에는 millis() 가 읽힐 때마다 1ms 진행하되,
     * 진행은 *두 번째* 읽기부터 — 첫 읽기는 고정값 그대로 반환한다.
     *
     * <p>이렇게 해야 sequence overflow 직후 nextId() 의 첫 clock.millis() 가 lastTimestamp 와
     * 같게 나와 waitNextMillis 로 진입하고 (= 그 경로를 실제로 검증), waitNextMillis 안의
     * busy-wait 읽기에서는 시각이 진행해 spin 이 자연스럽게 풀린다.</p>
     */
    static final class TickOnReadClock extends Clock {
        private final long baseMillis;
        private boolean ticking;
        private long reads;

        TickOnReadClock(Instant start) {
            this.baseMillis = start.toEpochMilli();
        }

        void startTicking() {
            this.ticking = true;
            this.reads = 0;
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
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            if (!ticking) {
                return baseMillis;
            }
            // 첫 읽기(reads==0)는 base, 이후 읽기마다 +1ms.
            return baseMillis + (reads++);
        }
    }
}
