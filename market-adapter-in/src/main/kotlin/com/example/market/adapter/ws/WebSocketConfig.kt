package com.example.market.adapter.ws

import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val orderBookQuery: OrderBookQueryUseCase,
    private val objectMapper: ObjectMapper,
) : WebSocketConfigurer {

    @Bean
    fun orderBookHandler(): OrderBookWebSocketHandler =
        OrderBookWebSocketHandler(orderBookQuery, objectMapper)

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(orderBookHandler(), "/ws/orderbook")
            .setAllowedOriginPatterns("*")
    }
}
