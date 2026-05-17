package com.example.market.adapter.out.messaging

import com.example.market.adapter.out.persistence.outbox.OutboxJpaEntity
import com.example.market.adapter.out.persistence.outbox.OutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Outbox 패턴의 발행 측 — Outbox 테이블에 쌓인 미발행 행을 주기적으로 읽어 Kafka 로 보내고
 * 발행 완료(markPublished) 표시한다.
 *
 * 설계 메모:
 * - **파이프라인 발행** — Kafka producer 는 보낼 메시지를 자체 in-flight 큐 (전송 중인
 *   메시지를 모아두는 producer 안의 버퍼) 에 쌓고 백그라운드 스레드가 브로커로 흘려보낸다.
 *   이 점을 이용해 한 배치의 모든 행에 대해 먼저 `kafka.send()` 를 한꺼번에 다 호출
 *   (= in-flight 큐에 다 적재) 한 뒤, 반환된 Future 를 순서대로 await 한다.
 *     - 이전 방식 (행 단위 직렬): send → `Future.get(5s)` → markPublished → 다음 행.
 *       배치 100건, 평균 RTT 20ms 라 100 × 20 ≈ 2초 걸린다.
 *     - 파이프라인 방식: 100건이 동시에 in-flight 로 흘러 브로커가 병렬 ack 하므로 첫
 *       번째 await 가 풀리는 시점에 대부분 이미 ack 도착 — 총 시간이 ms 수준.
 *
 *   **순서 보존 주의**: 같은 aggregate 의 이벤트 (예: 한 Trade 의 단계별 이벤트) 는
 *   발행 순서가 깨지면 안 된다. Kafka producer 는 같은 key 면 같은 파티션, 같은 파티션
 *   안에서는 producer 가 보낸 순서를 유지하기 때문에 — idempotent producer 가 켜져 있고
 *   `max.in.flight.requests.per.connection ≤ 5` 인 한 — 파이프라인 발행에서도
 *   같은 aggregateId 메시지는 순서가 보존된다.
 * - Kafka send 는 동기 (`Future.get(timeout)`) — 브로커 응답을 받아야 발행 완료
 *   처리한다. 응답 안 기다리는 fire-and-forget (보내고 잊기 — 실패해도 모름) 방식은 안 쓴다.
 * - 발행 완료 표시는 별도 트랜잭션 ([TransactionTemplate]) 에서. Kafka send 에 성공한
 *   행만 markPublished 가 commit 되고, 실패한 행은 미발행 상태로 남아 다음 폴링에서 자동
 *   재시도된다. `@Transactional` 어노테이션 자기 호출은 Spring 의 프록시 메커니즘
 *   (어노테이션 트랜잭션은 다른 빈에서 부를 때만 적용되고 같은 클래스 안의 호출엔 안 먹힘)
 *   에 막히기 때문에 프로그래매틱 트랜잭션을 쓴다.
 * - topic 이름 규칙: `"market." + eventType.toLowerCase()`.
 */
@Component
@ConditionalOnProperty(name = ["market.outbox.relay.enabled"], havingValue = "true")
open class OutboxRelay(
    private val outbox: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${market.outbox.relay.batch-size:100}")
    private var batchSize: Int = 100

    @Value("\${market.outbox.relay.send-timeout-ms:5000}")
    private var sendTimeoutMs: Long = 5000L

    @Value("\${market.outbox.relay.topic-prefix:market.}")
    private lateinit var topicPrefix: String

    // REQUIRES_NEW — Kafka 발행 후 markPublished 만 별도 트랜잭션. 한 행 실패가 다른 행에
    // 번지지 않도록 짧게 끊는다.
    private val markPublishedTx: TransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private data class InFlight(
        val row: OutboxJpaEntity,
        val future: CompletableFuture<SendResult<String, String>>,
    )

    @Scheduled(fixedDelayString = "\${market.outbox.relay.poll-interval-ms:1000}")
    open fun relay() {
        val batch = outbox.findUnpublished(PageRequest.of(0, batchSize))
        if (batch.isEmpty()) return

        // 1단계 — 배치 전체를 한 번에 producer 의 in-flight 큐에 적재 (파이프라인).
        // send() 는 즉시 반환하므로 직렬 await 보다 훨씬 빠르다.
        val inflight = ArrayList<InFlight>(batch.size)
        for (row in batch) {
            val topic = topicPrefix + row.eventType!!.lowercase()
            inflight.add(
                InFlight(row, kafka.send(topic, row.aggregateId!!, row.payload!!)),
            )
        }

        // 2단계 — Future 를 순서대로 기다린다. 한 행 timeout 이 다른 행을 막지 않도록
        // 각 await 에 동일한 sendTimeoutMs 를 적용. 실패한 행은 미발행 상태 그대로 두어
        // 다음 폴링에서 재시도.
        var success = 0
        var failure = 0
        for (item in inflight) {
            try {
                item.future.get(sendTimeoutMs, TimeUnit.MILLISECONDS)
                markPublished(item.row)
                success++
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failure++
                log.warn("outbox relay interrupted at row {}", item.row.id)
                // 남은 future 는 아직 in-flight 라 producer 가 백그라운드에서 마무리한다.
                // 이번 배치는 여기서 종료.
                break
            } catch (e: ExecutionException) {
                failure++
                log.warn("outbox relay send failed for {}: {}", item.row.id, e.message)
                // 발행 완료 표시를 안 하므로 다음 폴링에서 자동으로 다시 시도된다
            } catch (e: TimeoutException) {
                failure++
                log.warn("outbox relay send failed for {}: {}", item.row.id, e.message)
            }
        }
        if (success + failure > 0) {
            log.info("outbox relay batch — success={} failure={}", success, failure)
        }
    }

    private fun markPublished(row: OutboxJpaEntity) {
        markPublishedTx.executeWithoutResult {
            outbox.markPublished(row.id!!, clock.instant())
        }
    }
}
