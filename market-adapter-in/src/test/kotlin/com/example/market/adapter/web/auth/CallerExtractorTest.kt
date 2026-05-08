package com.example.market.adapter.web.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant

/**
 * JWT 활성화 환경에서 X-User-Id 헤더로 호출자 신원을 위조할 수 없어야 한다.
 */
class CallerExtractorTest {

    @Test
    fun `dev mode (jwt disabled) accepts X-User-Id header`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-User-Id", "u-123")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            val extractor = CallerExtractor(jwtEnabled = false)
            val caller = extractor.from(jwt = null)
            assertThat(caller.userId().value()).isEqualTo("u-123")
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `dev mode (jwt disabled, no header) yields anonymous`() {
        val request = MockHttpServletRequest()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            val extractor = CallerExtractor(jwtEnabled = false)
            val caller = extractor.from(jwt = null)
            assertThat(caller.userId().value()).isEqualTo("anonymous")
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `prod mode (jwt enabled) rejects calls without principal`() {
        // 운영 환경에서 jwt 가 null 인데 X-User-Id 헤더만 들고 들어오면 인증 우회 시도 →
        // IllegalStateException 으로 차단해야 한다.
        val request = MockHttpServletRequest()
        request.addHeader("X-User-Id", "evil-user")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            val extractor = CallerExtractor(jwtEnabled = true)
            assertThatThrownBy { extractor.from(jwt = null) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("authentication required")
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }

    @Test
    fun `valid jwt populates userId and roles`() {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("u-42")
            .claim("roles", listOf("USER", "ADMIN"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build()
        val extractor = CallerExtractor(jwtEnabled = true)
        val caller = extractor.from(jwt)
        assertThat(caller.userId().value()).isEqualTo("u-42")
        assertThat(caller.roles()).containsExactlyInAnyOrder("USER", "ADMIN")
    }
}
