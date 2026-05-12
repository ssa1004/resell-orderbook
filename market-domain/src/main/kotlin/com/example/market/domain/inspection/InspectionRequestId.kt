package com.example.market.domain.inspection

import java.util.UUID

/**
 * 검수 요청 식별자.
 *
 * <p>Java 호환을 유지하기 위해 일반 data class 로 둔다. value class 로 가면 Java 호출자에서
 * 매개변수 mangling 이 발생해 {@code findById(id)} 같은 메서드가 호출 불가능해진다.</p>
 *
 * <p>{@code @get:JvmName("value")} 로 기존 record accessor {@code value()} 를 그대로 보존.</p>
 */
data class InspectionRequestId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): InspectionRequestId = InspectionRequestId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): InspectionRequestId = InspectionRequestId(UUID.fromString(s))
    }
}
