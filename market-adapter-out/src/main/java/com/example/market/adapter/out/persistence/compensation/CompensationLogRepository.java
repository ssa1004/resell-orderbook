package com.example.market.adapter.out.persistence.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompensationLogRepository
        extends JpaRepository<CompensationLogJpaEntity, CompensationLogJpaEntity.PK> {

    Optional<CompensationLogJpaEntity> findByOperationAndBusinessKey(String operation, String businessKey);
}
