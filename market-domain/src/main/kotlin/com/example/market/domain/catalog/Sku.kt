package com.example.market.domain.catalog

/**
 * Sku — Product 의 *판매 단위*. (size + variant) 조합. 거래는 Sku 단위.
 *
 * 예: Air Jordan 1 Chicago + size="270" + variant="Black"
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor (`id()`,
 * `productId()`, `size()`, `variant()`) 을 노출해 호출자 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class Sku(
    val id: SkuId,
    val productId: ProductId,
    val size: String,
    /** variant 는 nullable 허용 (시계처럼 사이즈만 있는 경우). */
    val variant: String?,
) {
    init {
        require(size.isNotBlank()) { "size must not be blank" }
    }

    companion object {
        @JvmStatic
        fun create(productId: ProductId, size: String, variant: String?): Sku =
            Sku(SkuId.newId(), productId, size, variant)

        @JvmStatic
        fun restore(id: SkuId, productId: ProductId, size: String, variant: String?): Sku =
            Sku(id, productId, size, variant)
    }
}
