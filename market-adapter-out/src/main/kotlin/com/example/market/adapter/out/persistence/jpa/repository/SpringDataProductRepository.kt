package com.example.market.adapter.out.persistence.jpa.repository

import com.example.market.adapter.out.persistence.jpa.entity.ProductJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpringDataProductRepository : JpaRepository<ProductJpaEntity, UUID> {
    fun findByBrand(brand: String): List<ProductJpaEntity>
}
