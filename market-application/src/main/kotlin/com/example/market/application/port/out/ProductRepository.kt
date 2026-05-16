package com.example.market.application.port.out

import com.example.market.domain.catalog.Product
import com.example.market.domain.catalog.ProductId
import java.util.Optional

interface ProductRepository {
    fun save(product: Product)
    fun findById(id: ProductId): Optional<Product>
    fun findByBrand(brand: String): List<Product>
}
