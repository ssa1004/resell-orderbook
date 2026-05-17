package com.example.market.application.service

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqAdminRateLimitedException
import com.example.market.application.dlq.DlqBulkDryRunResult
import com.example.market.application.dlq.DlqBulkJob
import com.example.market.application.dlq.DlqBulkJobNotFoundException
import com.example.market.application.dlq.DlqBulkRequest
import com.example.market.application.dlq.DlqBulkStatus
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.pagination.Cursor
import com.example.market.application.port.`in`.DlqBulkAdminUseCase
import com.example.market.application.port.`in`.DlqBulkSubmission
import com.example.market.application.port.out.AdminRateLimiter
import com.example.market.application.port.out.AuditLogPort
import com.example.market.application.port.out.DlqBulkJobRepository
import com.example.market.application.port.out.DlqMessageStore
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Bulk replay / discard — notification-hub ADR-0015 표준의 dry-run 강제 + async job 패턴.
 *
 * 흐름:
 *
 * 1. 첫 호출 (`confirm=false`): 필터에 매칭되는 메시지 수 + sample 만 계산해 반환
 *    ([DlqBulkSubmission.DryRun]). DB / Kafka 에 변경 없음. AuditAction.DLQ_BULK_DRYRUN 기록.
 * 2. 운영자가 영향 범위 확인 후 두 번째 호출 (`confirm=true`):
 *    - 같은 매칭 ID 목록 snapshot 을 [DlqBulkJobRepository.create] 로 박고
 *    - 별 worker 에서 chunk 별 처리 → 진행률 increment
 *    - finish 시 status=SUCCEEDED / FAILED 로 전이 + AuditAction.DLQ_BULK_FINISH 기록
 *
 * 동기 실행 (test 또는 single-pod dev) 은 [Executor] 로 caller-thread 를 주입하고, prod 는
 * thread pool 을 주입한다.
 */
@Service
open class DlqBulkAdminService(
    private val store: DlqMessageStore,
    private val jobs: DlqBulkJobRepository,
    private val rateLimiter: AdminRateLimiter,
    private val audit: AuditLogPort,
    private val clock: Clock,
    @Qualifier("dlqBulkWorkerExecutor") private val workerExecutor: Executor,
) : DlqBulkAdminUseCase {

    override fun bulkReplay(request: DlqBulkRequest): DlqBulkSubmission =
        submit(DlqAction.REPLAY, request)

    override fun bulkDiscard(request: DlqBulkRequest): DlqBulkSubmission {
        // DISCARD 는 운영자 사유가 필수 — REPLAY 와 다른 enforcement.
        if (request.reason.isNullOrBlank()) {
            throw com.example.market.application.dlq.DlqBulkValidationException(
                "bulk discard requires reason",
            )
        }
        return submit(DlqAction.DISCARD, request)
    }

    override fun findJob(jobId: String): DlqBulkJob =
        jobs.find(jobId).orElseThrow { DlqBulkJobNotFoundException(jobId) }

    private fun submit(action: DlqAction, request: DlqBulkRequest): DlqBulkSubmission {
        rateLimit(SCOPE_BULK, request.actor)

        val filter = filterFrom(request)
        val sample = store.countAndSample(filter, SAMPLE_SIZE)
        val now = clock.instant()

        if (!request.confirm) {
            audit.log(
                AuditLogPort.AuditEntry(
                    at = now,
                    actor = request.actor,
                    action = AuditLogPort.AuditAction.DLQ_BULK_DRYRUN,
                    targetId = "dryrun-${UUID.randomUUID()}",
                    tradeId = null,
                    skuId = request.skuId,
                    reason = request.reason,
                    outcome = "DRY_RUN",
                    meta = mapOf(
                        "action" to action.name,
                        "source" to request.source.name,
                        "matched" to sample.total.toString(),
                    ),
                ),
            )
            return DlqBulkSubmission.DryRun(
                DlqBulkDryRunResult(
                    action = action,
                    source = request.source,
                    matched = sample.total,
                    sample = sample.sample,
                ),
            )
        }

        val jobId = UUID.randomUUID().toString()
        val job = jobs.create(
            id = jobId,
            action = action,
            source = request.source,
            requestedBy = request.actor,
            totalCount = sample.total,
            now = now,
        )
        audit.log(
            AuditLogPort.AuditEntry(
                at = now,
                actor = request.actor,
                action = AuditLogPort.AuditAction.DLQ_BULK_START,
                targetId = jobId,
                tradeId = null,
                skuId = request.skuId,
                reason = request.reason,
                outcome = "QUEUED",
                meta = mapOf(
                    "action" to action.name,
                    "source" to request.source.name,
                    "totalCount" to sample.total.toString(),
                ),
            ),
        )

        // 작업 디스패치 — 동기 (test) / async (prod) 모두 같은 인터페이스. Throwable 잡아
        // job status 를 갱신해 멈춤 없이 운영자가 잡 상태로 진단 가능하게.
        workerExecutor.execute { runJob(jobId, action, request, filter) }

        return DlqBulkSubmission.Queued(job)
    }

    private fun runJob(jobId: String, action: DlqAction, request: DlqBulkRequest, filter: DlqQuery) {
        val startedAt = clock.instant()
        jobs.markRunning(jobId, startedAt)
        var failed = false
        try {
            val ids = store.matchingIds(filter)
            ids.forEach { messageId ->
                try {
                    when (action) {
                        DlqAction.REPLAY -> {
                            val outcome = store.replay(messageId, clock.instant())
                            jobs.recordProgress(jobId, outcome.success, outcome.errorMessage, clock.instant())
                        }
                        DlqAction.DISCARD -> {
                            store.discard(messageId, clock.instant())
                            jobs.recordProgress(jobId, true, null, clock.instant())
                        }
                    }
                } catch (e: RuntimeException) {
                    // 한 메시지의 실패가 작업 전체를 멈추지 않게 — chunk 별 격리. 다음 메시지로 계속.
                    log.warn("bulk {} item failed jobId={} messageId={} reason={}",
                        action, jobId, messageId, e.message)
                    jobs.recordProgress(jobId, false, e.message, clock.instant())
                }
            }
        } catch (e: RuntimeException) {
            // store.matchingIds 실패 등 — 작업 자체가 진행 불가. status=FAILED 로 박는다.
            failed = true
            log.error("bulk {} job aborted jobId={} reason={}", action, jobId, e.message, e)
        }

        val finishedAt = clock.instant()
        val finalStatus = if (failed) DlqBulkStatus.FAILED else DlqBulkStatus.SUCCEEDED
        jobs.markFinished(jobId, finalStatus, finishedAt)
        val finalJob = jobs.find(jobId).orElse(null)
        audit.log(
            AuditLogPort.AuditEntry(
                at = finishedAt,
                actor = request.actor,
                action = AuditLogPort.AuditAction.DLQ_BULK_FINISH,
                targetId = jobId,
                tradeId = null,
                skuId = request.skuId,
                reason = request.reason,
                outcome = finalStatus.name,
                meta = mapOf(
                    "action" to action.name,
                    "source" to request.source.name,
                    "successCount" to (finalJob?.successCount?.toString() ?: "?"),
                    "failureCount" to (finalJob?.failureCount?.toString() ?: "?"),
                ),
            ),
        )
    }

    private fun filterFrom(request: DlqBulkRequest): DlqQuery = DlqQuery(
        source = request.source,
        topic = request.topic,
        errorType = request.errorType,
        from = request.from,
        to = request.to,
        skuId = request.skuId,
        cursor = Cursor.empty(),
        size = SAMPLE_SIZE,
    )

    private fun rateLimit(scope: String, actor: String) {
        val decision = rateLimiter.tryAcquire(scope, actor)
        if (!decision.allowed) {
            throw DlqAdminRateLimitedException(decision.retryAfter.seconds.coerceAtLeast(1))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DlqBulkAdminService::class.java)

        const val SCOPE_BULK: String = "dlq.bulk"
        const val SAMPLE_SIZE: Int = 20
    }
}
