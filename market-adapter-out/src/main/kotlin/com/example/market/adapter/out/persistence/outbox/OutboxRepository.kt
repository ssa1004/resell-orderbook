package com.example.market.adapter.out.persistence.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OutboxRepository : JpaRepository<OutboxJpaEntity, UUID> {

    @Query("SELECT o FROM OutboxJpaEntity o WHERE o.publishedAt IS NULL ORDER BY o.occurredAt ASC")
    fun findUnpublished(pageable: Pageable): List<OutboxJpaEntity>

    @Modifying
    @Query("UPDATE OutboxJpaEntity o SET o.publishedAt = :now WHERE o.id = :id")
    fun markPublished(@Param("id") id: UUID, @Param("now") now: Instant): Int
}
