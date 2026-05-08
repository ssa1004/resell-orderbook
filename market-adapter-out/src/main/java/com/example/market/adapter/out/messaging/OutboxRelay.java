package com.example.market.adapter.out.messaging;

import com.example.market.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.market.adapter.out.persistence.outbox.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 패턴의 발행 측 — Outbox 테이블에 쌓인 미발행 행을 주기적으로 읽어 Kafka 로 보내고
 * 발행 완료(markPublished) 표시한다.
 *
 * <p>설계 메모:</p>
 * <ul>
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

        int success = 0;
        int failure = 0;
        for (OutboxJpaEntity row : batch) {
            try {
                String topic = topicPrefix + row.getEventType().toLowerCase();
                kafka.send(topic, row.getAggregateId(), row.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                markPublished(row);
                success++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure++;
                log.warn("outbox relay interrupted at row {}", row.getId());
                break;
            } catch (ExecutionException | TimeoutException e) {
                failure++;
                log.warn("outbox relay send failed for {}: {}", row.getId(), e.getMessage());
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
