package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.SkuJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataSkuRepository
import com.example.market.application.port.out.SkuRepository
import com.example.market.domain.catalog.ProductId
import com.example.market.domain.catalog.Sku
import com.example.market.domain.catalog.SkuId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaSkuRepositoryAdapter(
    private val jpa: SpringDataSkuRepository,
) : SkuRepository {

    override fun save(sku: Sku) {
        jpa.save(SkuJpaMapper.toEntity(sku))
    }

    override fun findById(id: SkuId): Optional<Sku> =
        jpa.findById(id.value).map(SkuJpaMapper::toDomain)

    override fun findByProductId(productId: ProductId): List<Sku> =
        jpa.findByProductId(productId.value).map(SkuJpaMapper::toDomain)
}
