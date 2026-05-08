package com.example.market.application.service;

import com.example.market.application.port.out.CompensationLogStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CompensationGuard} — 외부 호출이 정확히 한 번만 일어나는지 + 캐시 hit 거동 + 동시 begin
 * race 처리.
 */
class CompensationGuardTest {

    private static final Instant NOW = Instant.parse("2026-05-08T12:00:00Z");
    private static final String OP = "REFUND";
    private static final String KEY = "trade-1";

    private InMemoryCompensationLogStore store;
    private CompensationGuard guard;

    @BeforeEach
    void setUp() {
        store = new InMemoryCompensationLogStore();
        guard = new CompensationGuard(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstCall_executesAction_andRecordsCompleted() {
        AtomicInteger calls = new AtomicInteger();

        var outcome = guard.runOnce(OP, KEY, prev -> {
            calls.incrementAndGet();
            return CompensationGuard.Outcome.completed("ext-1", "OK", "approved", "domain-result");
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.externalId()).isEqualTo("ext-1");
        assertThat(store.find(OP, KEY)).hasValueSatisfying(e -> {
            assertThat(e.isCompleted()).isTrue();
            assertThat(e.externalId()).isEqualTo("ext-1");
        });
    }

    @Test
    void secondCall_returnsCached_withoutExecutingAction() {
        // 1차 호출 — 외부 호출 발생.
        guard.runOnce(OP, KEY, prev -> CompensationGuard.Outcome.completed(
                "ext-1", "OK", "approved", "first"));

        // 2차 호출 — 캐시 hit, action 호출 안 됨.
        AtomicInteger calls = new AtomicInteger();
        var outcome = guard.runOnce(OP, KEY, prev -> {
            calls.incrementAndGet();
            return CompensationGuard.Outcome.completed("ext-2", "OK", "different", "second");
        });

        assertThat(calls.get()).isZero();
        // externalId 가 1차와 동일 — *외부에 두 번 호출되지 않았음*.
        assertThat(outcome.externalId()).isEqualTo("ext-1");
        assertThat(outcome.responseCode()).isEqualTo("OK");
    }

    @Test
    void failedFirstCall_thenRetry_returnsCachedFailureOutcome() {
        // 1차 — FAILED.
        guard.runOnce(OP, KEY, prev -> CompensationGuard.Outcome.failed("REJECTED", "PG down", null));

        // 2차 — FAILED 캐시 그대로 반환 (호출자는 같은 키로 재시도하지 말고 RETRY: prefix 권장 — 본
        // ADR 의 RetryRefund 흐름).
        AtomicInteger calls = new AtomicInteger();
        var outcome = guard.runOnce(OP, KEY, prev -> {
            calls.incrementAndGet();
            return CompensationGuard.Outcome.completed("ext-x", "OK", "should not run", null);
        });

        assertThat(calls.get()).isZero();
        assertThat(outcome.completed()).isFalse();
        assertThat(outcome.responseMessage()).isEqualTo("PG down");
    }

    @Test
    void actionThrows_recordsFailedAndRethrows() {
        assertThatThrownBy(() -> guard.runOnce(OP, KEY, prev -> {
            throw new RuntimeException("network error");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("network error");

        // 로그가 FAILED 로 박혀 있어야 다음 호출 시 캐시된 실패가 반환됨.
        assertThat(store.find(OP, KEY)).hasValueSatisfying(e -> {
            assertThat(e.isFailed()).isTrue();
            assertThat(e.responseCode()).isEqualTo("EXCEPTION");
        });
    }

    @Test
    void inProgress_byOtherWorker_throwsDuplicateInProgress() {
        // 외부에서 (다른 worker) IN_PROGRESS 로 begin 한 상태를 simulate.
        store.begin(OP, KEY, NOW);

        assertThatThrownBy(() -> guard.runOnce(OP, KEY, prev ->
                CompensationGuard.Outcome.completed("ext-1", "OK", "x", null)))
                .isInstanceOf(CompensationGuard.DuplicateInProgressException.class);
    }

    @Test
    void differentOperation_sameBusinessKey_areIndependent() {
        // 같은 trade 의 REFUND 와 SETTLE_PAYOUT 은 별도 operation — 독립.
        guard.runOnce("REFUND", KEY, prev ->
                CompensationGuard.Outcome.completed("refund-1", "OK", "x", null));
        var outcome = guard.runOnce("SETTLE_PAYOUT", KEY, prev ->
                CompensationGuard.Outcome.completed("payout-1", "OK", "x", null));

        assertThat(outcome.externalId()).isEqualTo("payout-1");
    }

    /** in-memory store. JpaCompensationLogStore 는 별도 IT 에서 검증. */
    static final class InMemoryCompensationLogStore implements CompensationLogStore {
        private final Map<String, Entry> rows = new ConcurrentHashMap<>();
        private static String k(String op, String key) { return op + "|" + key; }

        @Override public void begin(String op, String key, Instant now) {
            var prev = rows.putIfAbsent(k(op, key),
                    new Entry(op, key, Status.IN_PROGRESS, null, null, null, now, null));
            if (prev != null) throw new DuplicateBeginException(op, key);
        }
        @Override public void complete(String op, String key, String code, String msg, String externalId, Instant now) {
            rows.put(k(op, key),
                    new Entry(op, key, Status.COMPLETED, code, msg, externalId,
                            rows.get(k(op, key)).startedAt(), now));
        }
        @Override public void fail(String op, String key, String code, String msg, Instant now) {
            rows.put(k(op, key),
                    new Entry(op, key, Status.FAILED, code, msg, null,
                            rows.get(k(op, key)).startedAt(), now));
        }
        @Override public Optional<Entry> find(String op, String key) {
            return Optional.ofNullable(rows.get(k(op, key)));
        }
    }
}
