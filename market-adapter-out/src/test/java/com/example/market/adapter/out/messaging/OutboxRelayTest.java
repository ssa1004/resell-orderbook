package com.example.market.adapter.out.messaging;

import com.example.market.adapter.out.persistence.outbox.OutboxJpaEntity;
import com.example.market.adapter.out.persistence.outbox.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxRelay 동작 검증.
 *
 * <p>핵심 invariant:</p>
 * <ul>
 *   <li>Kafka send 가 성공한 행은 markPublished 가 호출된다 (= published_at UPDATE).
 *       이게 self-invocation 으로 깨져 있으면 운영에서 outbox 가 무한 polling 된다.</li>
 *   <li>Kafka send 가 실패한 행은 markPublished 가 호출되지 않는다 (다음 폴링에서 재시도).</li>
 *   <li>각 markPublished 는 별도 트랜잭션에서 실행된다 (한 행 실패가 다른 행에 번지지 않음).</li>
 * </ul>
 */
class OutboxRelayTest {

    private OutboxRepository outbox;
    private KafkaTemplate<String, String> kafka;
    private PlatformTransactionManager tm;
    private OutboxRelay relay;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(OutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        tm = mock(PlatformTransactionManager.class);
        // 트랜잭션 매니저는 단순 stub — getTransaction/commit 만 흉내. 새 트랜잭션이 시작될
        // 때마다 outbox.markPublished 가 정확히 호출되는지를 검증.
        when(tm.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        relay = new OutboxRelay(outbox, kafka, clock, tm);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "sendTimeoutMs", 1_000L);
        ReflectionTestUtils.setField(relay, "topicPrefix", "market.");
    }

    @Test
    void successfulSend_marksPublished() {
        OutboxJpaEntity row = newRow("TradeMatched", "trade-1");
        when(outbox.findUnpublished(any(Pageable.class))).thenReturn(List.of(row));
        when(kafka.send(eq("market.tradematched"), eq("trade-1"), any(String.class)))
                .thenReturn(completedSend("market.tradematched"));

        relay.relay();

        verify(outbox).markPublished(eq(row.getId()), eq(clock.instant()));
        verify(tm, times(1)).getTransaction(any());
        verify(tm, times(1)).commit(any(TransactionStatus.class));
    }

    @Test
    void failedSend_doesNotMarkPublished() {
        OutboxJpaEntity row = newRow("TradeMatched", "trade-2");
        when(outbox.findUnpublished(any(Pageable.class))).thenReturn(List.of(row));
        var failed = new CompletableFuture<SendResult<String, String>>();
        failed.completeExceptionally(new ExecutionException("kafka down", new RuntimeException()));
        when(kafka.send(any(String.class), any(String.class), any(String.class))).thenReturn(failed);

        relay.relay();

        verify(outbox, never()).markPublished(any(UUID.class), any(Instant.class));
        // 새 트랜잭션도 안 열림 — markPublished 는 호출조차 안 됨
        verify(tm, never()).getTransaction(any());
    }

    @Test
    void mixedBatch_eachPublishedRowGetsItsOwnTransaction() {
        OutboxJpaEntity ok1 = newRow("TradeMatched", "trade-A");
        OutboxJpaEntity ok2 = newRow("TradeCompleted", "trade-B");
        when(outbox.findUnpublished(any(Pageable.class))).thenReturn(List.of(ok1, ok2));
        when(kafka.send(any(String.class), any(String.class), any(String.class)))
                .thenAnswer(inv -> completedSend((String) inv.getArguments()[0]));

        relay.relay();

        verify(outbox).markPublished(eq(ok1.getId()), any(Instant.class));
        verify(outbox).markPublished(eq(ok2.getId()), any(Instant.class));
        // 행마다 별도 트랜잭션
        verify(tm, times(2)).getTransaction(any());
        verify(tm, times(2)).commit(any(TransactionStatus.class));
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private OutboxJpaEntity newRow(String type, String aggId) {
        OutboxJpaEntity row = new OutboxJpaEntity();
        row.setId(UUID.randomUUID());
        row.setEventType(type);
        row.setAggregateId(aggId);
        row.setPayload("{\"seq\":" + SEQ.incrementAndGet() + "}");
        row.setOccurredAt(Instant.parse("2026-05-03T23:59:00Z"));
        return row;
    }

    private static CompletableFuture<SendResult<String, String>> completedSend(String topic) {
        var record = new ProducerRecord<String, String>(topic, "k", "v");
        var meta = new RecordMetadata(new TopicPartition(topic, 0), 0L, 0, 0L, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(record, meta));
    }
}
