package com.example.market.adapter.web.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * dev/test 용 — 모든 요청 허용.
 * {@code market.security.jwt.enabled} 가 false 또는 미설정일 때 활성.
 */
@Configuration
@ConditionalOnProperty(name = ["market.security.jwt.enabled"], havingValue = "false", matchIfMissing = true)
class PermissiveSecurityConfig {

    @Bean
    fun permissiveFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }
}
