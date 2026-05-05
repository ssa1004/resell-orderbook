package com.example.market.adapter.out.messaging;

import com.example.market.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.market.adapter.out.persistence.outbox.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Outbox 테이블의 unpublished row 를 polling → Kafka publish → markPublished.
 *
 * <p>중요한 결정:</p>
 * <ul>
 *   <li><strong>동기 send</strong> + Future.get(timeout) — 응답이 와야 markPublished. fire-and-forget X.</li>
 *   <li>markPublished 는 *별도 트랜잭션* — Kafka 성공한 row 만 commit.
 *       publish 실패 시 그 row 는 다음 polling 에서 재시도.</li>
 *   <li>topic = {@code "market." + eventType.toLowerCase()} (예: market.trademetched)</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "market.outbox.relay.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    @Value("${market.outbox.relay.batch-size:100}")
    private int batchSize;

    @Value("${market.outbox.relay.send-timeout-ms:5000}")
    private long sendTimeoutMs;

    @Value("${market.outbox.relay.topic-prefix:market.}")
    private String topicPrefix;

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
                // 다음 polling 에서 재시도
            }
        }
        if (success + failure > 0) {
            log.info("outbox relay batch — success={} failure={}", success, failure);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markPublished(OutboxJpaEntity row) {
        outbox.markPublished(row.getId(), clock.instant());
    }
}
