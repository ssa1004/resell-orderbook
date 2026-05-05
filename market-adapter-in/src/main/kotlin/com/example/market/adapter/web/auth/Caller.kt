package com.example.market.adapter.web.auth

import com.example.market.application.context.Caller
import com.example.market.domain.shared.UserId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * JWT → Application Caller 매핑.
 *
 * <p>Subject(sub) → UserId. Roles 는 "roles" claim 에서 파싱.
 * JWT 가 없는 dev 환경에서는 X-User-Id 헤더를 보조로 받아 여러 사용자 시나리오를 시뮬레이션할 수 있다
 * (헤더도 없으면 "anonymous").</p>
 */
object CallerExtractor {

    fun from(jwt: Jwt?): Caller {
        if (jwt != null) {
            val roles: Set<String> = (jwt.getClaim<List<String>?>("roles") ?: emptyList()).toSet()
            return Caller(UserId.of(jwt.subject), roles)
        }
        val devUserId = currentRequest()?.getHeader("X-User-Id")
        return Caller.anonymous(devUserId ?: "anonymous")
    }

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
