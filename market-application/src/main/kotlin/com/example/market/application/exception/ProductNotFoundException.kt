package com.example.market.application.exception

import com.example.market.domain.catalog.ProductId

class ProductNotFoundException(id: ProductId) : RuntimeException("product not found: $id")
