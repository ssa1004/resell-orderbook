package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.SkuJpaEntity
import com.example.market.domain.catalog.ProductId
import com.example.market.domain.catalog.Sku
import com.example.market.domain.catalog.SkuId

object SkuJpaMapper {

    @JvmStatic
    fun toEntity(s: Sku): SkuJpaEntity {
        val e = SkuJpaEntity()
        e.id = s.id.value
        e.productId = s.productId.value
        e.size = s.size
        e.variant = s.variant
        return e
    }

    @JvmStatic
    fun toDomain(e: SkuJpaEntity): Sku = Sku.restore(
        SkuId(e.id!!),
        ProductId(e.productId!!),
        e.size!!,
        e.variant,
    )
}
