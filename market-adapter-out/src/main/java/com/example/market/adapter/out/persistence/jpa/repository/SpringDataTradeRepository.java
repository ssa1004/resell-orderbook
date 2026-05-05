package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.TradeJpaEntity;
import com.example.market.domain.trading.TradeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataTradeRepository extends JpaRepository<TradeJpaEntity, UUID> {

    @Query("""
            SELECT t FROM TradeJpaEntity t
            WHERE t.status = com.example.market.domain.trading.TradeStatus.CREATED
              AND t.createdAt < :cutoff
            ORDER BY t.createdAt ASC
            """)
    List<TradeJpaEntity> findStaleCreated(@Param("cutoff") Instant cutoff, Pageable pageable);

    List<TradeJpaEntity> findByStatus(TradeStatus status, Pageable pageable);
}
