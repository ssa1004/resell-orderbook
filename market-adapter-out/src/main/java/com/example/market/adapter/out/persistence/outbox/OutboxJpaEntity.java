package com.example.market.adapter.out.persistence.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox row — DB 트랜잭션과 같은 단위로 INSERT 되어 atomic 이벤트 발행을 보장.
 * OutboxRelay 가 published_at IS NULL 인 row 를 polling → Kafka publish → markPublished.
 */
@Entity
@Table(name = "outbox", indexes = {
        @Index(name = "ix_outbox_unpublished", columnList = "published_at, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
public class OutboxJpaEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;       // JSON-serialized

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;  // null = unpublished
}
