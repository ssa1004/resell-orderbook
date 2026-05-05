package com.example.market.adapter.out.persistence.idempotency;

import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryIdempotencyKeyStoreTest {

    @Test
    void firstAcquire_succeeds() {
        var store = new InMemoryIdempotencyKeyStore();
        store.acquireOrThrow("k1");
        // 다른 키는 OK
        store.acquireOrThrow("k2");
    }

    @Test
    void duplicateAcquire_throws() {
        var store = new InMemoryIdempotencyKeyStore();
        store.acquireOrThrow("dup");
        assertThatThrownBy(() -> store.acquireOrThrow("dup"))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void concurrentAcquire_onlyOneSucceeds() throws InterruptedException {
        var store = new InMemoryIdempotencyKeyStore();
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
}
