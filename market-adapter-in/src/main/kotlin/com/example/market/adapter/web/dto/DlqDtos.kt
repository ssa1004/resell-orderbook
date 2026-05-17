package com.example.market.adapter.web.dto

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqBulkDryRunResult
import com.example.market.application.dlq.DlqBulkJob
import com.example.market.application.dlq.DlqBulkRequest
import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqSource
import com.example.market.application.dlq.DlqStats
import com.example.market.application.dlq.DlqStatsBucket
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Duration
import java.time.Instant

/**
 * DLQ 관리 콘솔 REST API 의 요청/응답 DTO 묶음.
 *
 * 응답 DTO 는 application 의 record 를 그대로 직렬화하지 않고 한 layer 감싸서 노출.
 * 이유:
 *
 * 1. 클라이언트 호환성 — application 의 record 필드 변경이 REST 응답에 자동 반영되는 것을 막음
 * 2. enum / Instant 같은 값을 JSON 으로 명시 직렬화 — Kotlin record 의 기본 직렬화 의존 X
 */
data class DlqMessageResponse(
    val messageId: String,
    val source: String,
    val topic: String,
    val errorType: String,
    val errorMessage: String,
    val occurredAt: Instant,
    val attemptCount: Int,
    val tradeId: String?,
    val skuId: String?,
) {
    companion object {
        fun from(m: DlqMessage): DlqMessageResponse = DlqMessageResponse(
            messageId = m.messageId,
            source = m.source.name,
            topic = m.topic,
            errorType = m.errorType,
            errorMessage = m.errorMessage,
            occurredAt = m.occurredAt,
            attemptCount = m.attemptCount,
            tradeId = m.tradeId,
            skuId = m.skuId,
        )
    }
}

data class DlqMessageDetailResponse(
    val summary: DlqMessageResponse,
    val payload: String,
    val stackTrace: String,
    val headers: Map<String, String>,
    val partition: Int?,
    val offset: Long?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
) {
    companion object {
        fun from(d: DlqMessageDetail): DlqMessageDetailResponse = DlqMessageDetailResponse(
            summary = DlqMessageResponse.from(d.summary),
            payload = d.payload,
            stackTrace = d.stackTrace,
            headers = d.headers,
            partition = d.partition,
            offset = d.offset,
            firstSeenAt = d.firstSeenAt,
            lastSeenAt = d.lastSeenAt,
        )
    }
}

data class DlqActionRequest(
    @field:Size(max = 500) val reason: String?,
)

data class DlqActionResponse(
    val messageId: String,
    val action: String,
    val performedAt: Instant,
    val actor: String,
    val reason: String?,
    val tradeId: String?,
    val skuId: String?,
)

data class DlqBulkActionRequest(
    @field:NotBlank val source: String,
    val topic: String?,
    val errorType: String?,
    val from: Instant?,
    val to: Instant?,
    val skuId: String?,
    // confirm 이 false 이거나 누락이면 dry-run 강제. notification-hub ADR-0015 의 표준.
    val confirm: Boolean = false,
    @field:Size(max = 500) val reason: String?,
) {
    fun toCommand(actor: String): DlqBulkRequest = DlqBulkRequest(
        source = DlqSource.valueOf(source.uppercase()),
        topic = topic,
        errorType = errorType,
        from = from,
        to = to,
        skuId = skuId,
        confirm = confirm,
        reason = reason,
        actor = actor,
    )
}

data class DlqBulkDryRunResponse(
    val mode: String,
    val action: String,
    val source: String,
    val matched: Long,
    val sample: List<DlqMessageResponse>,
) {
    companion object {
        fun from(r: DlqBulkDryRunResult): DlqBulkDryRunResponse = DlqBulkDryRunResponse(
            mode = "DRY_RUN",
            action = r.action.name,
            source = r.source.name,
            matched = r.matched,
            sample = r.sample.map(DlqMessageResponse::from),
        )
    }
}

data class DlqBulkJobResponse(
    val mode: String,
    val id: String,
    val action: String,
    val source: String,
    val requestedBy: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val status: String,
    val totalCount: Long,
    val processedCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val firstError: String?,
) {
    companion object {
        fun from(j: DlqBulkJob, mode: String = "QUEUED"): DlqBulkJobResponse = DlqBulkJobResponse(
            mode = mode,
            id = j.id,
            action = j.action.name,
            source = j.source.name,
            requestedBy = j.requestedBy,
            createdAt = j.createdAt,
            startedAt = j.startedAt,
            finishedAt = j.finishedAt,
            status = j.status.name,
            totalCount = j.totalCount,
            processedCount = j.processedCount,
            successCount = j.successCount,
            failureCount = j.failureCount,
            firstError = j.firstError,
        )
    }
}

data class DlqStatsResponse(
    val from: Instant,
    val to: Instant,
    val bucket: Duration,
    val total: Long,
    val buckets: List<DlqStatsBucketResponse>,
    val bySource: Map<String, Long>,
    val byErrorType: List<NamedCount>,
    val bySku: List<NamedCount>,
) {
    companion object {
        fun from(s: DlqStats): DlqStatsResponse = DlqStatsResponse(
            from = s.from,
            to = s.to,
            bucket = s.bucket,
            total = s.total,
            buckets = s.buckets.map(DlqStatsBucketResponse::from),
            bySource = s.bySource.mapKeys { it.key.name },
            byErrorType = s.byErrorType.map { NamedCount(it.errorType, it.count) },
            bySku = s.bySku.map { NamedCount(it.skuId, it.count) },
        )
    }
}

data class DlqStatsBucketResponse(val start: Instant, val count: Long, val bySource: Map<String, Long>) {
    companion object {
        fun from(b: DlqStatsBucket): DlqStatsBucketResponse =
            DlqStatsBucketResponse(b.start, b.count, b.bySource.mapKeys { it.key.name })
    }
}

data class NamedCount(val name: String, val count: Long)

/** Action 응답 빌더 — application result → 응답 매핑. */
internal fun com.example.market.application.dlq.DlqActionResult.toResponse(): DlqActionResponse =
    DlqActionResponse(messageId, action.name, performedAt, actor, reason, tradeId, skuId)

internal fun DlqAction.label(): String = name
