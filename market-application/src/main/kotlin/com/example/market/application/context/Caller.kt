package com.example.market.application.context

import com.example.market.domain.shared.UserId

/**
 * 인증된 호출자의 컨텍스트. Adapter-in 이 JWT 에서 추출해서 service 메서드에 전달.
 *
 * 도메인 레이어에는 안 들어감 (도메인은 UserId 만 알면 충분). Application 의 권한 검사,
 * 로깅, idempotency 키 prefix 등에 사용.
 *
 * roles 는 생성자에서 방어적 복사 (`Set.copyOf`) — record component 시절 동작 보존.
 */
class Caller(
    @get:JvmName("userId") val userId: UserId,
    roles: Set<String>,
) {
    @get:JvmName("roles")
    val roles: Set<String> = java.util.Set.copyOf(roles)

    fun hasRole(role: String): Boolean = roles.contains(role)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Caller) return false
        return userId == other.userId && roles == other.roles
    }

    override fun hashCode(): Int = 31 * userId.hashCode() + roles.hashCode()

    override fun toString(): String = "Caller[userId=$userId, roles=$roles]"

    companion object {
        @JvmStatic
        fun of(userId: String, vararg roles: String): Caller =
            Caller(UserId.of(userId), setOf(*roles))

        @JvmStatic
        fun anonymous(fallbackId: String): Caller =
            Caller(UserId.of(fallbackId), emptySet())
    }
}
