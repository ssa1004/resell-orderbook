package com.example.market.adapter.out.persistence.outbox

import com.example.market.application.port.out.EventPublisher
import com.example.market.domain.shared.DomainEvent
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * EventPublisher 의 Outbox 구현 — 같은 트랜잭션의 outbox 테이블에 INSERT.
 * 별도 OutboxRelay 가 polling → Kafka 로 publish.
 */
@Component
class OutboxEventPublisher(
    private val outbox: OutboxRepository,
    private val objectMapper: ObjectMapper,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: DomainEvent) {
        val row = OutboxJpaEntity()
        row.id = UUID.randomUUID()
        row.aggregateId = event.aggregateId()
        row.eventType = event.javaClass.simpleName
        row.occurredAt = event.occurredAt()
        row.payload = try {
            objectMapper.writeValueAsString(event)
        } catch (e: JsonProcessingException) {
            throw IllegalStateException("failed to serialize event $event", e)
        }
        outbox.save(row)
        log.debug("outbox INSERT type={} aggregateId={}", row.eventType, row.aggregateId)
    }
}
