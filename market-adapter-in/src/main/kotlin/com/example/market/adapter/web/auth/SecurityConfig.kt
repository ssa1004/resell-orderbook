package com.example.market.adapter.web.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * 운영 — OAuth2 Resource Server (JWT). {@code market.security.jwt.enabled=true} 시 활성.
 *
 * <p>application.yml 의 {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} 사용.</p>
 */
@Configuration
@ConditionalOnProperty(name = ["market.security.jwt.enabled"], havingValue = "true")
class SecurityConfig {

    @Bean
    fun jwtFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/info", "/swagger/**",
                                   "/v3/api-docs/**", "/ws/**").permitAll()
                  .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                  .anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs -> rs.jwt { } }
        return http.build()
    }
}
