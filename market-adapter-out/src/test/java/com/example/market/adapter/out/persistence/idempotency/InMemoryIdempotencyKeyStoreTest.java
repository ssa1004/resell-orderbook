package com.example.market.adapter.out.persistence.idempotency;

import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryIdempotencyKeyStoreTest {

    private static InMemoryIdempotencyKeyStore newStore() {
        return new InMemoryIdempotencyKeyStore(Clock.systemUTC(), 24L);
    }

    @Test
    void firstAcquire_succeeds() {
        var store = newStore();
        store.acquireOrThrow("k1");
        // 다른 키는 OK
        store.acquireOrThrow("k2");
    }

    @Test
    void duplicateAcquire_throws() {
        var store = newStore();
        store.acquireOrThrow("dup");
        assertThatThrownBy(() -> store.acquireOrThrow("dup"))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void concurrentAcquire_onlyOneSucceeds() throws InterruptedException {
        var store = newStore();
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    store.acquireOrThrow("race-key");
                    success.incrementAndGet();
                } catch (DuplicateRequestException e) {
                    duplicate.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(done).isTrue();
        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicate.get()).isEqualTo(threads - 1);
    }

    @Test
    void expiredEntry_canBeReacquired() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-05-04T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
        var store = new InMemoryIdempotencyKeyStore(clock, 1L);  // 1시간

        store.acquireOrThrow("k");
        // 1시간 + 1초 후 → 만료됐으므로 재취득 가능
        nowRef.set(nowRef.get().plus(Duration.ofHours(1).plusSeconds(1)));
        store.acquireOrThrow("k");   // 다시 잡힘 — DuplicateRequestException 안 남
    }
}
