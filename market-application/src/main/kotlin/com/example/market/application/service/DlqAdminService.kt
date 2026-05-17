package com.example.market.application.service

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqActionResult
import com.example.market.application.dlq.DlqAdminRateLimitedException
import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqMessageNotFoundException
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqStats
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.CursorPage
import com.example.market.application.port.`in`.DlqAdminUseCase
import com.example.market.application.port.out.AdminRateLimiter
import com.example.market.application.port.out.AuditLogPort
import com.example.market.application.port.out.DlqMessageStore
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 단건 DLQ 조회 / 액션 / 통계 — notification-hub ADR-0015 + billing ADR-0033 의 표준 패턴.
 *
 * 책임:
 *
 * - 운영자 actor 키로 rate limit ([AdminRateLimiter]) 사전 적용
 * - replay / discard 액션 후 [AuditLogPort] 에 actor/reason/tradeId/skuId 기록
 * - hard delete 차단 — DISCARD 는 [DlqMessageStore] 의 soft delete 로만 보내고, 실제 hard
 *   delete 는 별도 retention worker 가 [DlqMessageStore.purgeBefore] 호출
 *
 * 멱등성:
 *
 * - [perform] 의 같은 (messageId, action) 두 번 호출 시 컨트롤러 단의 IdempotencyKeyStore 가
 *   1차 방어, 서비스 자체는 멱등성을 별도 보장 안 함 — replay 한 메시지가 다시 DLQ 에 떨어진
 *   경우 (정당한 재시도) 와 운영자 더블 클릭을 구분할 수 없기 때문.
 * - saga step 자체의 멱등성은 ADR-0023 의 compensation_log 가 책임진다 — replay 가 trade
 *   1건을 두 번 환불하는 일은 일어나지 않는다.
 */
@Service
open class DlqAdminService(
    private val store: DlqMessageStore,
    private val rateLimiter: AdminRateLimiter,
    private val audit: AuditLogPort,
    private val clock: Clock,
) : DlqAdminUseCase {

    override fun list(query: DlqQuery): CursorPage<DlqMessage> {
        rateLimit(SCOPE_READ, actorKeyForRead())
        return store.list(query)
    }

    override fun detail(messageId: String): DlqMessageDetail {
        rateLimit(SCOPE_READ, actorKeyForRead())
        return store.find(messageId).orElseThrow { DlqMessageNotFoundException(messageId) }
    }

    override fun perform(
        messageId: String,
        action: DlqAction,
        actor: String,
        reason: String?,
    ): DlqActionResult {
        require(actor.isNotBlank()) { "actor must not be blank" }
        if (action == DlqAction.DISCARD) {
            require(!reason.isNullOrBlank()) { "discard requires reason" }
        }
        rateLimit(SCOPE_WRITE, actor)

        // 대상 존재 확인 — 없으면 404. detail 호출의 rate-limit 영향을 피하기 위해 store 직접
        // 호출.
        val detail = store.find(messageId).orElseThrow { DlqMessageNotFoundException(messageId) }
        val now = clock.instant()
        when (action) {
            DlqAction.REPLAY -> {
                val outcome = store.replay(messageId, now)
                if (!outcome.success) {
                    log.warn(
                        "DLQ replay failed messageId={} actor={} attempts={} reason={}",
                        messageId, actor, outcome.newAttemptCount, outcome.errorMessage,
                    )
                }
                audit.log(
                    AuditLogPort.AuditEntry(
                        at = now,
                        actor = actor,
                        action = AuditLogPort.AuditAction.DLQ_REPLAY,
                        targetId = messageId,
                        tradeId = detail.summary.tradeId,
                        skuId = detail.summary.skuId,
                        reason = reason,
                        outcome = if (outcome.success) "OK" else "FAILED",
                        meta = mapOf(
                            "source" to detail.summary.source.name,
                            "topic" to detail.summary.topic,
                            "attemptCount" to outcome.newAttemptCount.toString(),
                        ),
                    ),
                )
            }
            DlqAction.DISCARD -> {
                store.discard(messageId, now)
                audit.log(
                    AuditLogPort.AuditEntry(
                        at = now,
                        actor = actor,
                        action = AuditLogPort.AuditAction.DLQ_DISCARD,
                        targetId = messageId,
                        tradeId = detail.summary.tradeId,
                        skuId = detail.summary.skuId,
                        reason = reason,
                        outcome = "SOFT_DELETED",
                        meta = mapOf(
                            "source" to detail.summary.source.name,
                            "topic" to detail.summary.topic,
                        ),
                    ),
                )
            }
        }

        return DlqActionResult(
            messageId = messageId,
            action = action,
            performedAt = now,
            actor = actor,
            reason = reason,
            tradeId = detail.summary.tradeId,
            skuId = detail.summary.skuId,
        )
    }

    override fun stats(query: DlqStatsQuery): DlqStats {
        rateLimit(SCOPE_READ, actorKeyForRead())
        return store.stats(query)
    }

    private fun rateLimit(scope: String, actor: String) {
        val decision = rateLimiter.tryAcquire(scope, actor)
        if (!decision.allowed) {
            throw DlqAdminRateLimitedException(decision.retryAfter.seconds.coerceAtLeast(1))
        }
    }

    // list / detail / stats 는 actor 가 굳이 필요 없고 controller 가 IP 를 직접 채워주는 게
    // 더 자연스러우나, 본 서비스는 application 계층이라 HTTP 컨텍스트가 없다 — controller
    // 에서 actor 를 perform 에 넣어주듯이 read scope 의 키도 IP 기반이어야 하지만 여기서는
    // 모든 read 가 한 키를 공유하는 "그룹 limit" 으로 단순화한다. 더 정밀한 per-IP limit 이
    // 필요해지면 read 흐름에 actor 파라미터를 추가하면 된다.
    private fun actorKeyForRead(): String = ACTOR_READ_SHARED

    companion object {
        private val log = LoggerFactory.getLogger(DlqAdminService::class.java)

        const val SCOPE_READ: String = "dlq.read"
        const val SCOPE_WRITE: String = "dlq.write"

        /** read scope 의 공용 키 — 모든 운영자의 read 요청이 한 통의 토큰을 공유. */
        private const val ACTOR_READ_SHARED: String = "shared"
    }
}
