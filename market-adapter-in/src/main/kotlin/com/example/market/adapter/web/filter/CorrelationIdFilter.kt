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
 */
@Component
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "correlationId"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cid = request.getHeader(HEADER) ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, cid)
        response.setHeader(HEADER, cid)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}
