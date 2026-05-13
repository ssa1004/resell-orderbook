package com.example.market.domain.catalog

import java.time.Instant

/**
 * 상품 마스터. 한정판 신발/시계/의류의 *모델* 정보.
 *
 * 예: Product = "Air Jordan 1 Retro High OG Chicago" (브랜드 = Nike, 카테고리 = SNEAKERS)
 * Sku = (이 상품의) 270mm / Black
 *
 * Product 자체는 거래 단위가 아니다. 거래는 Sku 단위로 이뤄짐.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor (`id()`,
 * `brand()`, `modelName()` 등) 을 노출해 호출자 호환성 (Java + Kotlin) 보존. 기존
 * Java class 가 final field + record-style accessor 패턴이라 사실상 record 의 의미와
 * 동일했고, Kotlin 변환으로 record 본연의 형태로 표현.
 */
@JvmRecord
data class Product(
    val id: ProductId,
    val brand: String,
    val modelName: String,
    /** 예: 555088-101. */
    val styleCode: String?,
    val category: ProductCategory,
    /** 한정판은 출시일이 의미 있음. nullable. */
    val releaseDate: Instant?,
    val imageUrl: String?,
    val createdAt: Instant,
) {

    companion object {
        @JvmStatic
        fun create(
            brand: String,
            modelName: String,
            styleCode: String?,
            category: ProductCategory,
            releaseDate: Instant?,
            imageUrl: String?,
            now: Instant,
        ): Product {
            require(brand.isNotBlank()) { "brand must not be blank" }
            require(modelName.isNotBlank()) { "modelName must not be blank" }
            return Product(
                id = ProductId.newId(),
                brand = brand,
                modelName = modelName,
                styleCode = styleCode,
                category = category,
                releaseDate = releaseDate,
                imageUrl = imageUrl,
                createdAt = now,
            )
        }

        @JvmStatic
        fun restore(
            id: ProductId,
            brand: String,
            modelName: String,
            styleCode: String?,
            category: ProductCategory,
            releaseDate: Instant?,
            imageUrl: String?,
            createdAt: Instant,
        ): Product = Product(
            id = id,
            brand = brand,
            modelName = modelName,
            styleCode = styleCode,
            category = category,
            releaseDate = releaseDate,
            imageUrl = imageUrl,
            createdAt = createdAt,
        )
    }
}
