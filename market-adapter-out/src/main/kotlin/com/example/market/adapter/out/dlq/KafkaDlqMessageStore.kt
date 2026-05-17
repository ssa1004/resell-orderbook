package com.example.market.adapter.out.dlq

import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqStats
import com.example.market.application.dlq.DlqStatsQuery
import com.example.market.application.pagination.CursorPage
import com.example.market.application.port.out.DlqMessageStore
import java.time.Instant
import java.util.Optional
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 운영 (prod) 용 Kafka DLT 기반 store — Spring Kafka 의 DefaultErrorHandler +
 * DeadLetterPublishingRecoverer ([com.example.market.adapter.out.messaging.DlqHandlerConfig])
 * 가 부려주는 `<원본>-dlt` 토픽을 listen 해 본 store 에 적재 + 운영자 replay 시 원래 토픽으로
 * 재발행.
 *
 * 본 클래스는 **스켈레톤** — DLT 토픽을 consume 해 in-process 저장소에 누적하는 listener 와
 * replay 시 [KafkaTemplate] 으로 원래 토픽에 publish 하는 흐름의 fan-in 만 짚었다. 운영
 * tier 가 들어오면 다음 후속을 진행:
 *
 * 1. DLT 토픽들 (`market.*-dlt`) 을 wildcard 구독해 메시지를 JPA `dlq_message` 테이블에 저장
 * 2. payload 에서 `tradeId` / `skuId` 를 파싱해 [DlqMessage] 채움 — EventPayloads 의 parser
 *    재사용
 * 3. [replay] 가 원래 토픽 (= topic.removeSuffix("-dlt")) 으로 KafkaTemplate.send 후 결과
 *    status 갱신
 * 4. 조회 / stats 는 JPA query 로 (인덱스: `source, occurred_at`, `sku_id, occurred_at`)
 *
 * 현재 단계에서는 운영 어댑터 자리 + 매핑 로직만 잡아두고, 실제 listener 와 JPA 엔티티는 별
 * 후속 작업 (Kafka 가 dev 에서 꺼져 있고 운영 인프라가 별도 일정) 에서 채운다.
 */
@Component
@ConditionalOnProperty(name = ["market.dlq.store.kafka.enabled"], havingValue = "true")
class KafkaDlqMessageStore(
    private val kafka: KafkaTemplate<String, String>,
) : DlqMessageStore {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.warn(
            "KafkaDlqMessageStore 스켈레톤 — DLT listener / JPA 적재가 채워질 때까지 모든 호출이 비활성 응답을 반환합니다. " +
                "운영 진입 전 후속 작업 필요.",
        )
    }

    override fun save(message: DlqMessageDetail) {
        // 운영에서는 DLT listener 가 호출 — 현재는 in-memory store 가 fallback 이라 noop.
    }

    override fun list(query: DlqQuery): CursorPage<DlqMessage> = CursorPage.last(emptyList())

    override fun find(messageId: String): Optional<DlqMessageDetail> = Optional.empty()

    override fun countAndSample(filter: DlqQuery, sampleSize: Int): DlqMessageStore.CountSample =
        DlqMessageStore.CountSample(0, emptyList())

    override fun matchingIds(filter: DlqQuery): List<String> = emptyList()

    override fun replay(messageId: String, now: Instant): DlqMessageStore.ReplayOutcome {
        // 후속: store 에서 detail 조회 → 원래 토픽으로 republish → success/failure 응답.
        log.warn("Kafka DLQ replay called but store skeleton — messageId={}", messageId)
        return DlqMessageStore.ReplayOutcome(false, 0, "kafka DLQ store skeleton — not yet implemented")
    }

    override fun discard(messageId: String, now: Instant) {
        log.warn("Kafka DLQ discard called but store skeleton — messageId={}", messageId)
    }

    override fun stats(query: DlqStatsQuery): DlqStats =
        DlqStats(query.from, query.to, query.bucket, 0, emptyList(), emptyMap(), emptyList(), emptyList())

    override fun purgeBefore(threshold: Instant): Int = 0
}
