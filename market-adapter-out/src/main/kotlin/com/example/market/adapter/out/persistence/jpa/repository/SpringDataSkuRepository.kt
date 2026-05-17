package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.SkuJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataSkuRepository : JpaRepository<SkuJpaEntity, UUID> {
    fun findByProductId(productId: UUID): List<SkuJpaEntity>
}
