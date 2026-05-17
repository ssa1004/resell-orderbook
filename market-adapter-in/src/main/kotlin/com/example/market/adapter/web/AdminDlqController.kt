package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.adapter.web.dto.CursorPageResponse
import com.example.market.adapter.web.dto.DlqActionRequest
import com.example.market.adapter.web.dto.DlqActionResponse
import com.example.market.adapter.web.dto.DlqBulkActionRequest
import com.example.market.adapter.web.dto.DlqBulkDryRunResponse
import com.example.market.adapter.web.dto.DlqBulkJobResponse
import com.example.market.adapter.web.dto.DlqMessageDetailResponse
import com.example.market.adapter.web.dto.DlqMessageResponse
import com.example.market.adapter.web.dto.DlqStatsResponse
import com.example.market.adapter.web.dto.toResponse
import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqSource
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.Cursor
import com.example.market.application.port.`in`.DlqAdminUseCase
import com.example.market.application.port.`in`.DlqBulkAdminUseCase
import com.example.market.application.port.`in`.DlqBulkSubmission
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.time.Duration
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 DLQ 콘솔 (ADR-0028).
 *
 * notification-hub admin v2 (ADR-0015) + billing (ADR-0033) 의 endpoint 표준을 그대로 따르고,
 * market 특유의 source / skuId 차원만 추가:
 *
 * - `GET    /api/v1/admin/dlq` — 목록 (source / topic / errorType / from / to / skuId / cursor / size)
 * - `GET    /api/v1/admin/dlq/{messageId}` — 단건 상세
 * - `POST   /api/v1/admin/dlq/{messageId}/replay` — 단건 replay
 * - `POST   /api/v1/admin/dlq/{messageId}/discard` — 단건 discard (reason 필수)
 * - `POST   /api/v1/admin/dlq/bulk-replay` — 대량 replay (dry-run 강제)
 * - `POST   /api/v1/admin/dlq/bulk-discard` — 대량 discard (dry-run 강제, reason 필수)
 * - `GET    /api/v1/admin/dlq/bulk-jobs/{jobId}` — 작업 폴링
 * - `GET    /api/v1/admin/dlq/stats` — 시간 bucket / source / sku 통계
 *
 * 모든 endpoint 는 [`hasRole('ADMIN')`][PreAuthorize] 강제. dev 는 JWT 비활성이라 같은 path
 * 가 permitAll 이라 ADMIN role 체크가 통과되지만, prod 의 [SecurityConfig] 가 다중 방어로
 * URL 매칭 + method security 둘 다 적용.
 *
 * actor 는 JWT subject 에서 추출, 없으면 `X-Actor` 헤더 → 그래도 없으면 `anonymous`. read
 * 흐름은 actor 가 굳이 필요 없어 service 내부에서 공용 키로 처리.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@Tag(name = "admin-dlq", description = "운영자 DLQ 콘솔 — 거래 saga 실패 메시지 관리")
@PreAuthorize("hasRole('ADMIN')")
@Validated
class AdminDlqController(
    private val dlqAdmin: DlqAdminUseCase,
    private val dlqBulk: DlqBulkAdminUseCase,
    private val callerExtractor: CallerExtractor,
) {

    @GetMapping
    @Operation(summary = "DLQ 메시지 목록 (cursor pagination)")
    fun list(
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) errorType: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) skuId: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") size: Int,
    ): ResponseEntity<CursorPageResponse<DlqMessageResponse>> {
        val query = DlqQuery(
            source = source?.let { runCatching { DlqSource.valueOf(it.uppercase()) }.getOrNull() },
            topic = topic,
            errorType = errorType,
            from = from,
            to = to,
            skuId = skuId,
            cursor = Cursor.of(cursor),
            size = size,
        )
        val page = dlqAdmin.list(query)
        return ResponseEntity.ok(
            CursorPageResponse(
                items = page.items.map(DlqMessageResponse::from),
                nextCursor = page.nextCursor().getOrNull()?.takeUnless { it.isEmpty() }?.token,
            ),
        )
    }

    @GetMapping("/{messageId}")
    @Operation(summary = "DLQ 메시지 단건 상세")
    fun detail(@PathVariable messageId: String): ResponseEntity<DlqMessageDetailResponse> {
        val detail = dlqAdmin.detail(messageId)
        return ResponseEntity.ok(DlqMessageDetailResponse.from(detail))
    }

    @PostMapping("/{messageId}/replay")
    @Operation(summary = "DLQ 메시지 단건 replay — 원래 토픽으로 재발행")
    fun replay(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable messageId: String,
        @RequestBody(required = false) request: DlqActionRequest?,
    ): ResponseEntity<DlqActionResponse> {
        val actor = actor(jwt)
        val result = dlqAdmin.perform(messageId, DlqAction.REPLAY, actor, request?.reason)
        return ResponseEntity.accepted().body(result.toResponse())
    }

    @PostMapping("/{messageId}/discard")
    @Operation(summary = "DLQ 메시지 단건 discard — soft delete (reason 필수)")
    fun discard(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable messageId: String,
        @Valid @RequestBody request: DlqActionRequest,
    ): ResponseEntity<DlqActionResponse> {
        val actor = actor(jwt)
        val result = dlqAdmin.perform(messageId, DlqAction.DISCARD, actor, request.reason)
        return ResponseEntity.ok(result.toResponse())
    }

    @PostMapping("/bulk-replay")
    @Operation(summary = "대량 replay — confirm=false 면 dry-run 강제")
    fun bulkReplay(
        @AuthenticationPrincipal jwt: Jwt?,
        @Valid @RequestBody request: DlqBulkActionRequest,
    ): ResponseEntity<*> {
        val actor = actor(jwt)
        val submission = dlqBulk.bulkReplay(request.toCommand(actor))
        return toBulkResponse(submission)
    }

    @PostMapping("/bulk-discard")
    @Operation(summary = "대량 discard — confirm=false 면 dry-run, reason 필수")
    fun bulkDiscard(
        @AuthenticationPrincipal jwt: Jwt?,
        @Valid @RequestBody request: DlqBulkActionRequest,
    ): ResponseEntity<*> {
        val actor = actor(jwt)
        val submission = dlqBulk.bulkDiscard(request.toCommand(actor))
        return toBulkResponse(submission)
    }

    @GetMapping("/bulk-jobs/{jobId}")
    @Operation(summary = "Bulk 작업 폴링")
    fun job(@PathVariable jobId: String): ResponseEntity<DlqBulkJobResponse> {
        val job = dlqBulk.findJob(jobId)
        return ResponseEntity.ok(DlqBulkJobResponse.from(job))
    }

    @GetMapping("/stats")
    @Operation(summary = "시간 bucket / source / SKU 적재량 통계")
    fun stats(
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false, defaultValue = "PT1H") bucket: String,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false, defaultValue = "10") topSku: Int,
        @RequestParam(required = false, defaultValue = "10") topErrorType: Int,
    ): ResponseEntity<DlqStatsResponse> {
        val query = DlqStatsQuery(
            from = from,
            to = to,
            bucket = Duration.parse(bucket),
            source = source?.let { runCatching { DlqSource.valueOf(it.uppercase()) }.getOrNull() },
            topSku = topSku,
            topErrorType = topErrorType,
        )
        val result = dlqAdmin.stats(query)
        return ResponseEntity.ok(DlqStatsResponse.from(result))
    }

    private fun toBulkResponse(submission: DlqBulkSubmission): ResponseEntity<*> = when (submission) {
        is DlqBulkSubmission.DryRun ->
            ResponseEntity.ok(DlqBulkDryRunResponse.from(submission.result))
        is DlqBulkSubmission.Queued ->
            ResponseEntity.accepted().body(DlqBulkJobResponse.from(submission.job))
    }

    /**
     * JWT 가 있으면 subject 를 actor 로, 없으면 `X-Actor` 헤더 (dev 시뮬레이션), 그것도 없으면
     * "anonymous". prod 의 [SecurityConfig] 는 `/api/v1/admin` 하위 전체에 ADMIN 역할을
     * 강제하므로 실 운영에서 jwt 가 null 일 일은 없다.
     */
    private fun actor(jwt: Jwt?): String {
        if (jwt != null) return jwt.subject ?: "unknown"
        val caller = callerExtractor.from(null)
        return caller.userId.value.ifBlank { "anonymous" }
    }
}
