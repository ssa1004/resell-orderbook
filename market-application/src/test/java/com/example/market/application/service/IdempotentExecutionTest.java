package com.example.market.application.service;

import com.example.market.application.port.out.IdempotencyKeyStore;
import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 트랜잭션 rollback 시 키가 자동 release 되는지 검증 — legitimate retry DoS 회귀 방지. */
class IdempotentExecutionTest {

    private RecordingStore store;
    private IdempotentExecution execution;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        execution = new IdempotentExecution(store);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acquire_then_commit_keepsKeyHeld() {
        execution.acquireAndReleaseOnRollback("k1");

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        assertThat(store.held).containsKey("k1");
        assertThat(store.released).doesNotContain("k1");
    }

    @Test
    void acquire_then_rollback_releasesKey() {
        execution.acquireAndReleaseOnRollback("k2");

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        assertThat(store.released).contains("k2");
    }

    @Test
    void acquire_then_unknownStatus_alsoReleases() {
        execution.acquireAndReleaseOnRollback("k3");

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
        }

        assertThat(store.released).contains("k3");
    }

    @Test
    void duplicateKey_throwsBeforeRegisteringHook() {
        store.held.put("dup", true);
        store.failOnAcquire = "dup";

        assertThatThrownBy(() -> execution.acquireAndReleaseOnRollback("dup"))
                .isInstanceOf(DuplicateRequestException.class);

        // 다른 트랜잭션 점유라 release 호출되면 안 됨
        assertThat(store.released).isEmpty();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void noActiveTransaction_acquiresButNoSynchronizationRegistered() {
        TransactionSynchronizationManager.clearSynchronization();

        execution.acquireAndReleaseOnRollback("k4");

        assertThat(store.held).containsKey("k4");
        // 등록할 곳이 없으니 release 자동 호출도 없음.
    }

    /** 테스트용 in-memory 더블. */
    private static class RecordingStore implements IdempotencyKeyStore {
        final ConcurrentHashMap<String, Boolean> held = new ConcurrentHashMap<>();
        final List<String> released = new ArrayList<>();
        String failOnAcquire;

        @Override public void acquireOrThrow(String key) {
            if (key.equals(failOnAcquire)) throw new DuplicateRequestException(key);
            if (held.putIfAbsent(key, true) != null) throw new DuplicateRequestException(key);
        }

        @Override public void release(String key) {
            held.remove(key);
            released.add(key);
        }
    }
}
