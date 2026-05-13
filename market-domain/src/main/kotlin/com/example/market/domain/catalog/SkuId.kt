package com.example.market.domain.catalog

import java.util.UUID

/**
 * SKU 식별자 (Product 의 판매 단위).
 *
 * Java 호환을 유지하기 위해 일반 data class 로 둔다. value class 로 가면 Java 호출자에서
 * 매개변수 mangling 이 발생해 `findById(SkuId id)` 같은 메서드가 호출 불가능해진다.
 *
 * `@get:JvmName("value")` 로 기존 record accessor `value()` 를 그대로 보존.
 */
data class SkuId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): SkuId = SkuId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): SkuId = SkuId(UUID.fromString(s))
    }
}
