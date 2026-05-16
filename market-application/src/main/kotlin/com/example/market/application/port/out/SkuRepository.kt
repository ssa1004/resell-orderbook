package com.example.market.application.port.out

import com.example.market.domain.catalog.ProductId
import com.example.market.domain.catalog.Sku
import com.example.market.domain.catalog.SkuId
import java.util.Optional

interface SkuRepository {
    fun save(sku: Sku)
    fun findById(id: SkuId): Optional<Sku>
    fun findByProductId(productId: ProductId): List<Sku>
}
