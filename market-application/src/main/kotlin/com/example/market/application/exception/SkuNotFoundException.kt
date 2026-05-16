package com.example.market.application.exception

import com.example.market.domain.catalog.SkuId

class SkuNotFoundException(id: SkuId) : RuntimeException("sku not found: $id")
