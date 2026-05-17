package com.example.market.application.port.`in`

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqActionResult
import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqStats
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.CursorPage

/**
 * 운영자 DLQ 콘솔의 단건 / 조회 / 통계 use case.
 *
 * 거래 saga (ADR-0004) 의 각 단계가 DLQ 로 떨어진 메시지를 운영자가 조회하고 한 건씩
 * replay / discard 한다. 대량 처리는 [DlqBulkAdminUseCase] 가 담당.
 *
 * notification-hub ADR-0015 (admin v2) + billing ADR-0033 의 단건 API 와 같은 인터페이스.
 * market 특유는 [DlqQuery] 의 `skuId` 필터와 [DlqStats] 의 `bySku` 차원 (같은 sneaker 의
 * 동시 stuck 패턴 식별).
 */
interface DlqAdminUseCase {

    fun list(query: DlqQuery): CursorPage<DlqMessage>

    /**
     * @throws com.example.market.application.dlq.DlqMessageNotFoundException 없으면
     */
    fun detail(messageId: String): DlqMessageDetail

    /**
     * 단건 액션 — replay 는 원래 토픽으로 재발행, discard 는 soft delete + reason 기록.
     *
     * 같은 (messageId, action) 으로 두 번 호출되면 두 번째는 첫 결과를 그대로 반환 (멱등) —
     * 운영자 UI 의 더블 클릭 / 재시도가 두 번 publish 되지 않도록. 멱등 키는 어댑터의
     * IdempotencyKeyStore 에서 처리 (X-Idempotency-Key 헤더 또는 자동 생성).
     *
     * @param actor    감사 로그에 남길 운영자 식별자 (controller 가 JWT subject 에서 채움)
     * @param reason   DISCARD 시 필수, REPLAY 시 optional
     */
    fun perform(messageId: String, action: DlqAction, actor: String, reason: String?): DlqActionResult

    fun stats(query: DlqStatsQuery): DlqStats
}
