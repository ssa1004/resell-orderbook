package com.example.market.adapter.out.persistence.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Outbox row — DB 트랜잭션과 같은 단위로 INSERT 되어 atomic 이벤트 발행을 보장.
 * OutboxRelay 가 published_at IS NULL 인 row 를 polling → Kafka publish → markPublished.
 */
@Entity
@Table(
    name = "outbox",
    indexes = [
        Index(name = "ix_outbox_unpublished", columnList = "published_at, occurred_at"),
    ],
)
class OutboxJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "aggregate_id", nullable = false, length = 64)
    var aggregateId: String? = null

    @Column(name = "event_type", nullable = false, length = 100)
    var eventType: String? = null

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    var payload: String? = null // JSON-serialized

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null // null = unpublished
}
