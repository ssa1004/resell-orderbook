package com.example.market.adapter.out.persistence.compensation

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface CompensationLogRepository :
    JpaRepository<CompensationLogJpaEntity, CompensationLogJpaEntity.PK> {

    fun findByOperationAndBusinessKey(
        operation: String,
        businessKey: String,
    ): Optional<CompensationLogJpaEntity>
}
