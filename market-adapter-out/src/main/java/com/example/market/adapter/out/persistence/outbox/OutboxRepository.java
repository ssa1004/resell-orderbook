package com.example.market.adapter.out.persistence.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxJpaEntity, UUID> {

    @Query("SELECT o FROM OutboxJpaEntity o WHERE o.publishedAt IS NULL ORDER BY o.occurredAt ASC")
    List<OutboxJpaEntity> findUnpublished(org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("UPDATE OutboxJpaEntity o SET o.publishedAt = :now WHERE o.id = :id")
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);
}
