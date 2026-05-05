package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.RefundJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataRefundRepository extends JpaRepository<RefundJpaEntity, UUID> {
    Optional<RefundJpaEntity> findByTradeId(UUID tradeId);
}
