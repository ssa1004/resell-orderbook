package com.example.market.application.command

import com.example.market.domain.catalog.ProductCategory
import java.time.Instant

@JvmRecord
data class RegisterProductCommand(
    val brand: String,
    val modelName: String,
    val styleCode: String?,
    val category: ProductCategory,
    val releaseDate: Instant?,
    val imageUrl: String?,
)
