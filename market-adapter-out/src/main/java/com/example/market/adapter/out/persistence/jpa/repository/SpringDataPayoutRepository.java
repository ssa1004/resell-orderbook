package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.PayoutJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataPayoutRepository extends JpaRepository<PayoutJpaEntity, UUID> {
    Optional<PayoutJpaEntity> findByTradeId(UUID tradeId);
}
