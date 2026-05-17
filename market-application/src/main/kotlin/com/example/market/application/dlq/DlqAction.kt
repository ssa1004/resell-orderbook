package com.example.market.application.dlq

import java.time.Instant

/**
 * 운영자가 단일 / 대량 DLQ 메시지에 수행하는 두 가지 액션.
 *
 * - [REPLAY] : 같은 메시지를 원래 토픽으로 재발행. consumer 가 다시 처리한다. saga 의 각 단계는
 *              멱등 (ADR-0023) 이므로 중복 처리해도 안전.
 * - [DISCARD]: 메시지를 *soft delete* (status=DISCARDED) 로 표시. 운영자 reason 필수, 실 hard
 *              delete 는 retention 후 auto-purge 가 수행 (회계용 compensation_log 와의 일관성
 *              유지를 위해 동기 hard delete 차단).
 */
enum class DlqAction { REPLAY, DISCARD }

/**
 * 단일 DLQ 메시지의 액션 결과 — REST 응답에도 그대로 사용.
 */
@JvmRecord
data class DlqActionResult(
    val messageId: String,
    val action: DlqAction,
    val performedAt: Instant,
    val actor: String,
    val reason: String?,
    val tradeId: String?,
    val skuId: String?,
)
