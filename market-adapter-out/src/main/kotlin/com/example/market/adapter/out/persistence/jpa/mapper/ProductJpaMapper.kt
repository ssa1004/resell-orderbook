package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.ProductJpaEntity
import com.example.market.domain.catalog.Product
import com.example.market.domain.catalog.ProductId

object ProductJpaMapper {

    @JvmStatic
    fun toEntity(p: Product): ProductJpaEntity {
        val e = ProductJpaEntity()
        e.id = p.id.value
        e.brand = p.brand
        e.modelName = p.modelName
        e.styleCode = p.styleCode
        e.category = p.category
        e.releaseDate = p.releaseDate
        e.imageUrl = p.imageUrl
        e.createdAt = p.createdAt
        return e
    }

    @JvmStatic
    fun toDomain(e: ProductJpaEntity): Product = Product.restore(
        ProductId(e.id!!),
        e.brand!!,
        e.modelName!!,
        e.styleCode,
        e.category!!,
        e.releaseDate,
        e.imageUrl,
        e.createdAt!!,
    )
}
