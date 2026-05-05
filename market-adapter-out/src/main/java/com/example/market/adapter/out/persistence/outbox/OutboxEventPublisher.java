package com.example.market.adapter.out.persistence.outbox;

import com.example.market.application.port.out.EventPublisher;
import com.example.market.domain.shared.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * EventPublisher 의 Outbox 구현 — 같은 트랜잭션의 outbox 테이블에 INSERT.
 * 별도 OutboxRelay 가 polling → Kafka 로 publish.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher implements EventPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(DomainEvent event) {
        OutboxJpaEntity row = new OutboxJpaEntity();
        row.setId(UUID.randomUUID());
        row.setAggregateId(event.aggregateId());
        row.setEventType(event.getClass().getSimpleName());
        row.setOccurredAt(event.occurredAt());
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize event " + event, e);
        }
        outbox.save(row);
        log.debug("outbox INSERT type={} aggregateId={}", row.getEventType(), row.getAggregateId());
    }
}
