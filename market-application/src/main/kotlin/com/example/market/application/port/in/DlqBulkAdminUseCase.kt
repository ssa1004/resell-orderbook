package com.example.market.application.port.`in`

import com.example.market.application.dlq.DlqBulkDryRunResult
import com.example.market.application.dlq.DlqBulkJob
import com.example.market.application.dlq.DlqBulkRequest

/**
 * 대량 replay / discard — 같은 필터에 매칭되는 DLQ 메시지를 한 번에 처리. 운영자가 PG 장애
 * 복구 후 stuck 메시지 수백 건을 한 번에 풀 때 쓴다.
 *
 * `confirm=false` 면 항상 dry-run — [DlqBulkDryRunResult] 반환 (실 처리 없이 매칭 건수만).
 * 운영자가 영향 범위를 확인한 뒤 `confirm=true` 로 다시 호출하면 비동기 작업 큐잉 후
 * [DlqBulkJob] 반환. 진행률은 [findJob] 폴링.
 *
 * notification-hub ADR-0015 와 동일한 dry-run / async-job 패턴. billing ADR-0033 에서 hard
 * delete 차단 + retention 후 auto-purge 정책을 추가했고 본 도메인도 그대로 따른다.
 */
interface DlqBulkAdminUseCase {

    /** confirm=false 면 dry-run, true 면 작업 큐잉. 반환 union 은 sealed type 으로 표현. */
    fun bulkReplay(request: DlqBulkRequest): DlqBulkSubmission

    fun bulkDiscard(request: DlqBulkRequest): DlqBulkSubmission

    /**
     * @throws com.example.market.application.dlq.DlqBulkJobNotFoundException 없으면
     */
    fun findJob(jobId: String): DlqBulkJob
}

/**
 * bulk 요청의 결과 — dry-run 미리보기이거나 큐잉된 작업.
 */
sealed interface DlqBulkSubmission {
    @JvmRecord
    data class DryRun(val result: DlqBulkDryRunResult) : DlqBulkSubmission

    @JvmRecord
    data class Queued(val job: DlqBulkJob) : DlqBulkSubmission
}
