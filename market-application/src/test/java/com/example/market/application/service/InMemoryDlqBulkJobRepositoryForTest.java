package com.example.market.application.service;

import com.example.market.application.dlq.DlqAction;
import com.example.market.application.dlq.DlqBulkJob;
import com.example.market.application.dlq.DlqBulkStatus;
import com.example.market.application.dlq.DlqSource;
import com.example.market.application.port.out.DlqBulkJobRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Test 전용 in-memory job repository — 운영용 adapter (InMemoryDlqBulkJobRepository in adapter-out)
 * 과 동작은 비슷하지만 application 모듈에서 adapter-out 의존을 끌어오지 않기 위해 별 구현 둠.
 */
final class InMemoryDlqBulkJobRepositoryForTest implements DlqBulkJobRepository {

    private final ConcurrentMap<String, DlqBulkJob> jobs = new ConcurrentHashMap<>();

    @Override
    public DlqBulkJob create(String id, DlqAction action, DlqSource source,
                             String requestedBy, long totalCount, Instant now) {
        DlqBulkJob job = new DlqBulkJob(id, action, source, requestedBy, now,
                null, null, DlqBulkStatus.QUEUED, totalCount, 0, 0, 0, null);
        jobs.put(id, job);
        return job;
    }

    @Override
    public Optional<DlqBulkJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public void markRunning(String id, Instant now) {
        jobs.computeIfPresent(id, (k, j) -> new DlqBulkJob(j.id(), j.action(), j.source(),
                j.requestedBy(), j.createdAt(), now, j.finishedAt(),
                DlqBulkStatus.RUNNING, j.totalCount(), j.processedCount(),
                j.successCount(), j.failureCount(), j.firstError()));
    }

    @Override
    public void recordProgress(String id, boolean success, String errorMessage, Instant now) {
        jobs.computeIfPresent(id, (k, j) -> new DlqBulkJob(j.id(), j.action(), j.source(),
                j.requestedBy(), j.createdAt(), j.startedAt(), j.finishedAt(),
                j.status(), j.totalCount(), j.processedCount() + 1,
                j.successCount() + (success ? 1 : 0),
                j.failureCount() + (success ? 0 : 1),
                j.firstError() == null && errorMessage != null ? errorMessage : j.firstError()));
    }

    @Override
    public void markFinished(String id, DlqBulkStatus status, Instant now) {
        jobs.computeIfPresent(id, (k, j) -> new DlqBulkJob(j.id(), j.action(), j.source(),
                j.requestedBy(), j.createdAt(), j.startedAt(), now, status,
                j.totalCount(), j.processedCount(), j.successCount(), j.failureCount(), j.firstError()));
    }
}
