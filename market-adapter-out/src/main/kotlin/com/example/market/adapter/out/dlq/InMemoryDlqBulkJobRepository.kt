package com.example.market.adapter.out.dlq

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqBulkJob
import com.example.market.application.dlq.DlqBulkStatus
import com.example.market.application.dlq.DlqSource
import com.example.market.application.port.out.DlqBulkJobRepository
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * 단일 인스턴스용 in-memory bulk job repository.
 *
 * 인스턴스 재시작 시 작업이 lost 됨 — 운영 가이드는 "최근 24h 작업만 추적" 으로 단순화.
 * 운영 다인스턴스 환경에서는 JPA 또는 Redis 기반 구현으로 교체. 모든 메서드는
 * [ConcurrentHashMap] 단위로 atomic — recordProgress 의 increment 는 computeIfPresent 로
 * race-free.
 */
@Component
class InMemoryDlqBulkJobRepository : DlqBulkJobRepository {

    private val jobs = ConcurrentHashMap<String, DlqBulkJob>()

    override fun create(
        id: String,
        action: DlqAction,
        source: DlqSource,
        requestedBy: String,
        totalCount: Long,
        now: Instant,
    ): DlqBulkJob {
        val job = DlqBulkJob(
            id = id, action = action, source = source, requestedBy = requestedBy,
            createdAt = now, startedAt = null, finishedAt = null,
            status = DlqBulkStatus.QUEUED,
            totalCount = totalCount, processedCount = 0, successCount = 0, failureCount = 0,
            firstError = null,
        )
        jobs[id] = job
        return job
    }

    override fun find(id: String): Optional<DlqBulkJob> = Optional.ofNullable(jobs[id])

    override fun markRunning(id: String, now: Instant) {
        jobs.computeIfPresent(id) { _, j ->
            j.copy(startedAt = now, status = DlqBulkStatus.RUNNING)
        }
    }

    override fun recordProgress(id: String, success: Boolean, errorMessage: String?, now: Instant) {
        jobs.computeIfPresent(id) { _, j ->
            j.copy(
                processedCount = j.processedCount + 1,
                successCount = j.successCount + if (success) 1 else 0,
                failureCount = j.failureCount + if (success) 0 else 1,
                firstError = j.firstError ?: errorMessage,
            )
        }
    }

    override fun markFinished(id: String, status: DlqBulkStatus, now: Instant) {
        jobs.computeIfPresent(id) { _, j ->
            j.copy(finishedAt = now, status = status)
        }
    }
}
