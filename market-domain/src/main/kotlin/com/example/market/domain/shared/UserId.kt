package com.example.market.domain.shared

/**
 * 사용자 식별자 — buyer/seller/inspector 모두 같은 타입.
 *
 * 외부 IdP (OAuth2) 의 subject(sub) 를 그대로 사용. UUID 강제 안 함 — 외부 IdP 가 발급하는
 * 회원번호 형식 (숫자, 영숫자, ULID 등) 을 그대로 받아들인다.
 *
 * Java 호환을 유지하기 위해 일반 data class 로 둔다. value class 로 가면 Java 호출자에서
 * 매개변수 mangling 이 발생해 메서드 호출이 불가능해진다.
 *
 * `@get:JvmName("value")` 로 기존 record accessor `value()` 를 그대로 보존.
 */
data class UserId(@get:JvmName("value") val value: String) {

    init {
        require(value.isNotBlank()) { "userId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun of(value: String): UserId = UserId(value)
    }
}
