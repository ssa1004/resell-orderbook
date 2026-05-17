package com.example.market.application.port.out

import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqSource
import com.example.market.application.dlq.DlqStats
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.CursorPage
import java.time.Instant
import java.util.Optional

/**
 * DLQ 메시지 저장소 추상화 — 어댑터는 Kafka `-dlt` 토픽을 추적하면서 같은 데이터를 본
 * 인터페이스 형태로 노출. 본 도메인은 Kafka DLT (DefaultErrorHandler +
 * DeadLetterPublishingRecoverer, [com.example.market.adapter.out.messaging.DlqHandlerConfig])
 * 가 표준 흐름.
 *
 * 어댑터의 책임:
 *
 * - 토픽 listener 가 `-dlt` 메시지를 수신해 [save] 호출 — JPA 테이블 또는 별 store 에 적재
 * - [list] / [find] / [stats] 는 그 저장소를 조회
 * - [replay] 는 원래 토픽 (`-dlt` 제거) 으로 재발행 + status 갱신
 * - [discard] 는 soft delete (status=DISCARDED) — hard delete 는 [purge] 호출에서만,
 *   [purgeBefore] 가 retention 후 호출
 *
 * 어댑터는 Kafka 가 비활성인 dev 환경 (`market.kafka.dlq.enabled=false`) 에서는 in-memory
 * 구현을 제공한다.
 */
interface DlqMessageStore {

    fun save(message: DlqMessageDetail)

    fun list(query: DlqQuery): CursorPage<DlqMessage>

    fun find(messageId: String): Optional<DlqMessageDetail>

    /** 필터로 매칭되는 메시지 count + 샘플 (dry-run 용). */
    fun countAndSample(filter: DlqQuery, sampleSize: Int): CountSample

    /** 필터로 매칭되는 모든 메시지 ID — bulk job worker 가 chunk 처리. */
    fun matchingIds(filter: DlqQuery): List<String>

    /** 단건 replay — 원래 토픽으로 재발행. 발행 성공 시 status 갱신. */
    fun replay(messageId: String, now: Instant): ReplayOutcome

    /** 단건 discard — soft delete. reason 은 audit 에서 별도 기록. */
    fun discard(messageId: String, now: Instant)

    fun stats(query: DlqStatsQuery): DlqStats

    /**
     * retention 기간 지난 DISCARDED row 의 hard delete.
     *
     * @return purge 된 row 수
     */
    fun purgeBefore(threshold: Instant): Int

    /**
     * 운영자 console 의 "scope" — read / write / bulk 가 다른 rate limit 을 받기 위해 source 와
     * 액션을 묶어 키로 사용.
     */
    @JvmRecord
    data class CountSample(val total: Long, val sample: List<DlqMessage>)

    @JvmRecord
    data class ReplayOutcome(val success: Boolean, val newAttemptCount: Int, val errorMessage: String?)

    /**
     * 어댑터에서 source 가 어디인지 모를 때 fallback 으로 쓰는 토픽 → source 매핑 helper —
     * application 계층의 [DlqSource.fromTopic] 을 그대로 노출.
     */
    fun resolveSource(topic: String): DlqSource =
        DlqSource.fromTopic(topic) ?: DlqSource.OUTBOX

    /** action 의 라벨 — audit / 로그에서 사용. */
    fun label(action: DlqAction): String = when (action) {
        DlqAction.REPLAY -> "replay"
        DlqAction.DISCARD -> "discard"
    }
}
