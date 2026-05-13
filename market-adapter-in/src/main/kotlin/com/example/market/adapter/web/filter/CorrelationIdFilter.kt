package com.example.market.adapter.web.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * X-Request-Id 헤더를 MDC 의 correlationId 로 전파. 없으면 UUID 생성.
 *
 * <p>외부 입력이므로 로그 인젝션 / 메모리 폭주 방어:</p>
 * <ul>
 *   <li>길이 cap (MAX_LEN) — 한 줄 로그가 헤더로 채워지지 않도록</li>
 *   <li>허용 문자 — UUID/ULID/Snowflake 가 들어가도록 영숫자 + {@code - _ .} 만 허용</li>
 *   <li>위반 시 받은 값은 버리고 새 UUID 발급 (요청은 통과)</li>
 * </ul>
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "correlationId"
        private const val MAX_LEN = 128
        private val ALLOWED = Regex("^[A-Za-z0-9._-]+$")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cid = sanitize(request.getHeader(HEADER)) ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, cid)
        response.setHeader(HEADER, cid)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_LEN) return null
        return if (ALLOWED.matches(raw)) raw else null
    }
}
