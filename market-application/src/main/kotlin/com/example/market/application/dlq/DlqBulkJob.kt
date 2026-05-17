package com.example.market.application.dlq

import java.time.Instant

/**
 * Bulk 액션의 비동기 작업 — submit 시점에 status=QUEUED, worker 가 진행하며 RUNNING / SUCCEEDED
 * / FAILED 로 전이.
 *
 * 운영자는 `GET /admin/dlq/bulk-jobs/{jobId}` 로 폴링해 진행률을 본다 — UI 의 progress bar
 * 와 동일 흐름. notification-hub ADR-0015 / billing ADR-0033 과 같은 폴링 패턴.
 *
 * @param id            작업 ID — UUID
 * @param action        REPLAY / DISCARD
 * @param source        대상 source — 작업 안의 모든 메시지가 같은 source
 * @param requestedBy   요청 운영자
 * @param createdAt     submit 시각
 * @param startedAt     worker 가 RUNNING 으로 전이한 시각 — QUEUED 면 null
 * @param finishedAt    SUCCEEDED / FAILED 로 전이한 시각 — 그 외엔 null
 * @param status        현재 상태
 * @param totalCount    대상 메시지 총 수 (queue 시점 snapshot)
 * @param processedCount    이미 처리한 수 — totalCount 의 일부
 * @param successCount  성공 수 — processedCount 의 일부
 * @param failureCount  실패 수 — processedCount 의 일부 (총 = success + failure)
 * @param firstError    첫 에러 메시지 — 운영자가 trend 파악하는 단서
 */
@JvmRecord
data class DlqBulkJob(
    val id: String,
    val action: DlqAction,
    val source: DlqSource,
    val requestedBy: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val status: DlqBulkStatus,
    val totalCount: Long,
    val processedCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val firstError: String?,
)

enum class DlqBulkStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }
