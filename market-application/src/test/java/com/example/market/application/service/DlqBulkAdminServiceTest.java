package com.example.market.application.service;

import com.example.market.application.dlq.DlqAction;
import com.example.market.application.dlq.DlqBulkDryRunResult;
import com.example.market.application.dlq.DlqBulkJob;
import com.example.market.application.dlq.DlqBulkJobNotFoundException;
import com.example.market.application.dlq.DlqBulkRequest;
import com.example.market.application.dlq.DlqBulkStatus;
import com.example.market.application.dlq.DlqBulkValidationException;
import com.example.market.application.dlq.DlqSource;
import com.example.market.application.port.in.DlqBulkSubmission;
import com.example.market.application.port.out.AuditLogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DlqBulkAdminService} — bulk replay/discard dry-run/async-job 거동.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>confirm=false → 항상 dry-run, store 변경 0</li>
 *   <li>confirm=true → matchingIds 전체에 대해 처리, job status SUCCEEDED 로 전이</li>
 *   <li>bulk discard 는 reason 강제 (DlqBulkValidationException)</li>
 *   <li>SKU 필터 적용 시 그 SKU 만 처리됨 (market 특유 차원)</li>
 *   <li>Audit 에 DRYRUN / START / FINISH 가 모두 기록</li>
 * </ul>
 */
class DlqBulkAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");

    private InMemoryDlqMessageStore store;
    private InMemoryDlqBulkJobRepositoryForTest jobs;
    private DlqTestSupport.AlwaysAllowRateLimiter rateLimiter;
    private DlqTestSupport.CapturingAuditLog audit;
    private DlqBulkAdminService service;

    /** 동기 실행 — runJob 이 caller thread 에서 동작해 테스트가 결정적. */
    private static final Executor SYNC = Runnable::run;

    @BeforeEach
    void setUp() {
        store = new InMemoryDlqMessageStore();
        jobs = new InMemoryDlqBulkJobRepositoryForTest();
        rateLimiter = new DlqTestSupport.AlwaysAllowRateLimiter();
        audit = new DlqTestSupport.CapturingAuditLog();
        service = new DlqBulkAdminService(store, jobs, rateLimiter, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), SYNC);
    }

    @Test
    void dryRun_doesNotMutateStore() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));
        store.save(DlqTestSupport.makeMessage("m2", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-2", "sku-A"));

        DlqBulkSubmission result = service.bulkReplay(new DlqBulkRequest(
                DlqSource.REFUND, null, null, null, null, "sku-A",
                false, null, "ops-alice"));

        assertThat(result).isInstanceOf(DlqBulkSubmission.DryRun.class);
        DlqBulkDryRunResult dry = ((DlqBulkSubmission.DryRun) result).result();
        assertThat(dry.matched()).isEqualTo(2L);
        assertThat(dry.action()).isEqualTo(DlqAction.REPLAY);
        assertThat(store.replayed()).isEmpty();
        assertThat(store.discarded()).isEmpty();
        assertThat(audit.actions()).containsExactly(AuditLogPort.AuditAction.DLQ_BULK_DRYRUN);
    }

    @Test
    void confirmedBulkReplay_processesAllMatchingAndAuditsStartFinish() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));
        store.save(DlqTestSupport.makeMessage("m2", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-2", "sku-A"));
        store.save(DlqTestSupport.makeMessage("m3", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-3", "sku-B"));

        // SKU filter 로 sku-A 만 — market 특유 차원 적용.
        DlqBulkSubmission result = service.bulkReplay(new DlqBulkRequest(
                DlqSource.REFUND, null, null, null, null, "sku-A",
                true, null, "ops-alice"));

        assertThat(result).isInstanceOf(DlqBulkSubmission.Queued.class);
        DlqBulkJob job = ((DlqBulkSubmission.Queued) result).job();

        // 동기 executor 라 호출 끝난 시점에 이미 finished.
        DlqBulkJob finalState = service.findJob(job.id());
        assertThat(finalState.status()).isEqualTo(DlqBulkStatus.SUCCEEDED);
        assertThat(finalState.processedCount()).isEqualTo(2L);
        assertThat(finalState.successCount()).isEqualTo(2L);
        assertThat(finalState.failureCount()).isZero();
        // sku-B 는 건드리지 않았다.
        assertThat(store.replayed()).containsExactlyInAnyOrder("m1", "m2");

        assertThat(audit.actions()).containsExactlyInAnyOrder(
                AuditLogPort.AuditAction.DLQ_BULK_START,
                AuditLogPort.AuditAction.DLQ_BULK_FINISH);
    }

    @Test
    void bulkDiscard_requiresReason() {
        assertThatThrownBy(() -> service.bulkDiscard(new DlqBulkRequest(
                DlqSource.REFUND, null, null, null, null, null,
                true, null, "ops-alice")))
                .isInstanceOf(DlqBulkValidationException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void bulkDiscard_softDeletesAllMatching() {
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.OUTBOX,
                "market.outbox", "TimeoutException", NOW, null, null));
        store.save(DlqTestSupport.makeMessage("m2", DlqSource.OUTBOX,
                "market.outbox", "TimeoutException", NOW, null, null));

        service.bulkDiscard(new DlqBulkRequest(
                DlqSource.OUTBOX, null, null, null, null, null,
                true, "stale outbox after dev test run", "ops-bob"));

        assertThat(store.discarded()).containsExactlyInAnyOrder("m1", "m2");
    }

    @Test
    void findJob_unknownId_throws() {
        assertThatThrownBy(() -> service.findJob("missing-job"))
                .isInstanceOf(DlqBulkJobNotFoundException.class);
    }

    @Test
    void confirmedBulkReplay_partialFailuresRecordedNotAborted() {
        // 첫 메시지는 정상, 두 번째는 store.replay 에서 RuntimeException 던지도록 spy 처리.
        var failingStore = new InMemoryDlqMessageStore() {
            @Override
            public ReplayOutcome replay(String messageId, Instant now) {
                if ("m2".equals(messageId)) throw new RuntimeException("kafka down");
                return super.replay(messageId, now);
            }
        };
        failingStore.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));
        failingStore.save(DlqTestSupport.makeMessage("m2", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-2", "sku-A"));
        failingStore.save(DlqTestSupport.makeMessage("m3", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-3", "sku-A"));

        DlqBulkAdminService localService = new DlqBulkAdminService(failingStore, jobs, rateLimiter,
                audit, Clock.fixed(NOW, ZoneOffset.UTC), SYNC);

        DlqBulkSubmission result = localService.bulkReplay(new DlqBulkRequest(
                DlqSource.REFUND, null, null, null, null, null,
                true, null, "ops-alice"));

        DlqBulkJob job = service.findJob(((DlqBulkSubmission.Queued) result).job().id());
        // 작업은 한 메시지 실패에도 끝까지 진행 → SUCCEEDED + failureCount=1.
        assertThat(job.status()).isEqualTo(DlqBulkStatus.SUCCEEDED);
        assertThat(job.successCount()).isEqualTo(2L);
        assertThat(job.failureCount()).isEqualTo(1L);
        assertThat(job.firstError()).contains("kafka down");
    }

    @Test
    void multipleSourcesRequireSeparateBulkCalls() {
        // 같은 호출이 두 source 를 휘젓지 못하도록 — source 가 enum 필수 필드라는 게 곧
        // "한 번에 한 source" 강제.
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException", NOW, "trade-1", "sku-A"));
        store.save(DlqTestSupport.makeMessage("m2", DlqSource.PG_WEBHOOK,
                "market.pgwebhook", "TimeoutException", NOW, null, null));

        var dry = (DlqBulkSubmission.DryRun) service.bulkReplay(new DlqBulkRequest(
                DlqSource.REFUND, null, null, null, null, null,
                false, null, "ops-alice"));

        assertThat(dry.result().matched()).isEqualTo(1L);
    }

    @Test
    void dryRunDuration_acceptsWideWindow() {
        Instant start = NOW.minus(Duration.ofDays(1));
        store.save(DlqTestSupport.makeMessage("m1", DlqSource.REFUND,
                "market.inspectionfailed", "PgFailureException",
                start.plus(Duration.ofHours(1)), "trade-1", "sku-A"));

        DlqBulkSubmission result = service.bulkReplay(new DlqBulkRequest(
                DlqSource.REFUND, null, null, start, NOW, null,
                false, null, "ops-alice"));

        assertThat(((DlqBulkSubmission.DryRun) result).result().matched()).isEqualTo(1L);
    }
}
