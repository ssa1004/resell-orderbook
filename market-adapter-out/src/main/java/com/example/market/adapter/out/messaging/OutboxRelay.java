package com.example.market.adapter.out.messaging;

import com.example.market.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.market.adapter.out.persistence.outbox.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 패턴의 발행 측 — Outbox 테이블에 쌓인 미발행 행을 주기적으로 읽어 Kafka 로 보내고
 * 발행 완료(markPublished) 표시한다.
 *
 * <p>설계 메모:</p>
 * <ul>
 *   <li><b>파이프라인 발행</b> — 한 배치의 모든 행에 {@code kafka.send()} 를 먼저 모두 호출해
 *       producer 의 in-flight 큐에 적재한 뒤, 반환된 Future 를 순서대로 await 한다. 행마다
 *       {@code Future.get(5s)} 직렬로 기다리던 이전 방식은 (배치 100, 평균 RTT 20ms) 약 2초가
 *       걸리지만, 파이프라인은 같은 in-flight 가 병렬로 진행돼 ms 수준으로 줄어든다.
 *       Kafka producer 는 같은 key (= aggregateId) 의 메시지에 대해 파티션 순서를 보장하므로
 *       (idempotent producer + max.in.flight.requests.per.connection<=5) 같은 aggregate
 *       내 순서는 깨지지 않는다.</li>
 *   <li>Kafka send 는 동기 (Future.get(timeout)) — 브로커 응답이 와야 발행 완료 처리한다.
 *       응답을 안 기다리는 fire-and-forget (보내고 잊기) 방식은 안 쓴다.</li>
 *   <li>발행 완료 표시는 별도 트랜잭션 ({@link TransactionTemplate}) 에서. Kafka 가 성공한 행만
 *       commit 되고, 실패한 행은 그대로 미발행 상태로 남아 다음 폴링에서 자동 재시도된다.
 *       프록시 self-invocation 함정을 피하려고 {@code @Transactional} 자기 호출 대신
 *       프로그래매틱 트랜잭션을 쓴다.</li>
 *   <li>topic 이름 규칙: {@code "market." + eventType.toLowerCase()}.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "market.outbox.relay.enabled", havingValue = "true")
@Slf4j
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final TransactionTemplate markPublishedTx;

    @Value("${market.outbox.relay.batch-size:100}")
    private int batchSize;

    @Value("${market.outbox.relay.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    @Value("${market.outbox.relay.topic-prefix:market.}")
    private String topicPrefix;

    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        // REQUIRES_NEW — Kafka 발행 후 markPublished 만 별도 트랜잭션. 한 행 실패가 다른 행에
        // 번지지 않도록 짧게 끊는다.
        this.markPublishedTx = new TransactionTemplate(transactionManager);
        this.markPublishedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${market.outbox.relay.poll-interval-ms:1000}")
    public void relay() {
        List<OutboxJpaEntity> batch = outbox.findUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        // 1단계 — 배치 전체를 한 번에 producer 의 in-flight 큐에 적재 (파이프라인).
        // send() 는 즉시 반환하므로 직렬 await 보다 훨씬 빠르다.
        record InFlight(OutboxJpaEntity row, CompletableFuture<SendResult<String, String>> future) {}
        List<InFlight> inflight = new ArrayList<>(batch.size());
        for (OutboxJpaEntity row : batch) {
            String topic = topicPrefix + row.getEventType().toLowerCase();
            inflight.add(new InFlight(row,
                    kafka.send(topic, row.getAggregateId(), row.getPayload())));
        }

        // 2단계 — Future 를 순서대로 기다린다. 한 행 timeout 이 다른 행을 막지 않도록
        // 각 await 에 동일한 sendTimeoutMs 를 적용. 실패한 행은 미발행 상태 그대로 두어
        // 다음 폴링에서 재시도.
        int success = 0;
        int failure = 0;
        for (InFlight item : inflight) {
            try {
                item.future().get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                markPublished(item.row());
                success++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure++;
                log.warn("outbox relay interrupted at row {}", item.row().getId());
                // 남은 future 는 아직 in-flight 라 producer 가 백그라운드에서 마무리한다.
                // 이번 배치는 여기서 종료.
                break;
            } catch (ExecutionException | TimeoutException e) {
                failure++;
                log.warn("outbox relay send failed for {}: {}",
                        item.row().getId(), e.getMessage());
                // 발행 완료 표시를 안 하므로 다음 폴링에서 자동으로 다시 시도된다
            }
        }
        if (success + failure > 0) {
            log.info("outbox relay batch — success={} failure={}", success, failure);
        }
    }

    private void markPublished(OutboxJpaEntity row) {
        markPublishedTx.executeWithoutResult(status ->
                outbox.markPublished(row.getId(), clock.instant()));
    }
}
