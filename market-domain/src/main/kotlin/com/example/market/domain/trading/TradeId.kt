package com.example.market.domain.trading

import java.util.UUID

/**
 * Trade 식별자.
 *
 * Java 호환을 유지하기 위해 일반 data class 로 둔다. value class 로 가면 Java 호출자에서
 * 매개변수 mangling 이 발생해 메서드 호출이 불가능해진다.
 *
 * `@get:JvmName("value")` 로 기존 record accessor `value()` 를 그대로 보존.
 */
data class TradeId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): TradeId = TradeId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): TradeId = TradeId(UUID.fromString(s))
    }
}
