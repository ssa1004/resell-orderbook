package com.example.market.application.port.out

import com.example.market.domain.shared.DomainEvent

/**
 * 도메인 이벤트 발행. 구현은 *같은 트랜잭션* 의 Outbox 테이블에 INSERT (Outbox 패턴).
 * 별도 OutboxRelay 가 polling → Kafka publish → markPublished.
 */
interface EventPublisher {
    fun publish(event: DomainEvent)

    fun publishAll(vararg events: DomainEvent) {
        for (e in events) publish(e)
    }
}
