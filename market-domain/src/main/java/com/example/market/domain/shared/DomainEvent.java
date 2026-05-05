package com.example.market.domain.shared;

import java.time.Instant;

/**
 * 도메인 이벤트 마커. Outbox 로 발행되어 Kafka 컨슈머가 소비.
 */
public interface DomainEvent {
    String aggregateId();
    Instant occurredAt();
}
