package com.example.market.adapter.out.persistence.jpa

import com.example.market.adapter.out.persistence.jpa.mapper.ProductJpaMapper
import com.example.market.adapter.out.persistence.jpa.repository.SpringDataProductRepository
import com.example.market.application.port.out.ProductRepository
import com.example.market.domain.catalog.Product
import com.example.market.domain.catalog.ProductId
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class JpaProductRepositoryAdapter(
    private val jpa: SpringDataProductRepository,
) : ProductRepository {

    override fun save(product: Product) {
        jpa.save(ProductJpaMapper.toEntity(product))
    }

    override fun findById(id: ProductId): Optional<Product> =
        jpa.findById(id.value).map(ProductJpaMapper::toDomain)

    override fun findByBrand(brand: String): List<Product> =
        jpa.findByBrand(brand).map(ProductJpaMapper::toDomain)
}
