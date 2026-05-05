package com.example.market.adapter.web.dto

import com.example.market.application.command.RegisterProductCommand
import com.example.market.domain.catalog.Product
import com.example.market.domain.catalog.ProductCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class RegisterProductRequest(
    @field:NotBlank val brand: String,
    @field:NotBlank val modelName: String,
    val styleCode: String?,
    @field:NotNull val category: ProductCategory,
    val releaseDate: Instant?,
    val imageUrl: String?,
) {
    fun toCommand() = RegisterProductCommand(brand, modelName, styleCode, category, releaseDate, imageUrl)
}

data class ProductResponse(
    val id: String,
    val brand: String,
    val modelName: String,
    val styleCode: String?,
    val category: ProductCategory,
    val releaseDate: Instant?,
    val imageUrl: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(p: Product) = ProductResponse(
            id = p.id().toString(),
            brand = p.brand(),
            modelName = p.modelName(),
            styleCode = p.styleCode(),
            category = p.category(),
            releaseDate = p.releaseDate(),
            imageUrl = p.imageUrl(),
            createdAt = p.createdAt(),
        )
    }
}
