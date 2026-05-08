package com.example.market.adapter.web.auth

import com.example.market.application.context.Caller
import com.example.market.domain.shared.UserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * JWT → Application Caller 매핑.
 *
 * <p>운영 (JWT 활성화) — Subject(sub) → UserId, Roles 는 "roles" claim 에서 파싱.
 * JWT 가 없으면 인증 실패로 간주하고 IllegalStateException 던짐.</p>
 *
 * <p>dev (JWT 비활성화) — JWT 가 어차피 항상 null. {@code X-User-Id} 헤더로 여러 사용자
 * 시나리오를 흉내내고, 헤더도 없으면 "anonymous". 이 헤더는 인증 토큰이 아니라 단순
 * 시뮬레이션 입력이므로 운영 환경에서는 절대 신뢰하지 않는다.</p>
 */
@Component
class CallerExtractor(
    @Value("\${market.security.jwt.enabled:false}") private val jwtEnabled: Boolean,
) {

    fun from(jwt: Jwt?): Caller {
        if (jwt != null) {
            val roles: Set<String> = (jwt.getClaim<List<String>?>("roles") ?: emptyList()).toSet()
            return Caller(UserId.of(jwt.subject), roles)
        }
        // JWT 가 활성화된 환경에서 jwt 가 null 이라는 건 인증되지 않은 호출이라는 뜻.
        // 호스트 헤더(X-User-Id) 로 신원을 흉내내는 것을 차단한다 — 운영에서의 임포스터 방지.
        check(!jwtEnabled) { "authentication required (no JWT principal)" }
        val devUserId = currentRequest()?.getHeader("X-User-Id")
        return Caller.anonymous(devUserId ?: "anonymous")
    }

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
