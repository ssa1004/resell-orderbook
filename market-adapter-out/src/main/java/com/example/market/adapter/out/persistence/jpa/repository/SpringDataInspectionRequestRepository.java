package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.InspectionRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataInspectionRequestRepository
        extends JpaRepository<InspectionRequestJpaEntity, UUID> {

    Optional<InspectionRequestJpaEntity> findByTradeId(UUID tradeId);
}
