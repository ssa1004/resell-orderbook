package com.example.market.adapter.ws

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * STOMP over WebSocket 설정.
 *
 * <p>기존의 raw WebSocket ({@link WebSocketConfig}) 은 SKU 별로 한 connection 을 잡는 구조라
 * 1 client × N SKU 구독 시 N connection 이 떴다. STOMP 로 오면:
 * <ul>
 *   <li>1 connection 으로 N destination 구독 가능 (효율)</li>
 *   <li>{@code SUBSCRIBE} / {@code UNSUBSCRIBE} 가 protocol 표준 — 추가 cleanup 코드 불필요</li>
 *   <li>per-user destination ({@code /user/queue/notifications}) 으로 사용자별 직접 push</li>
 *   <li>Heartbeat 가 protocol 단에서 관리 (idle connection 자동 정리)</li>
 *   <li>Spring 의 {@link org.springframework.messaging.simp.SimpMessagingTemplate} 으로 publish 단순화</li>
 * </ul>
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code /ws} — STOMP handshake</li>
 *   <li>SUBSCRIBE {@code /topic/orderbook/{skuId}} — 호가창 push</li>
 *   <li>SUBSCRIBE {@code /user/queue/notifications} — 본인 거래 알림 (인증된 사용자만)</li>
 * </ul>
 *
 * <p>운영에서는 단일 인스턴스 in-memory broker 한계 (수만 connection 이상) 에 도달하면
 * relay broker (RabbitMQ STOMP, ActiveMQ Artemis) 로 교체. 현재는 in-memory 로 시작.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
class StompConfig : WebSocketMessageBrokerConfigurer {

    /**
     * STOMP heartbeat 발사용 scheduler.
     * `setHeartbeatValue` 만 켜고 scheduler 가 없으면 SimpleBrokerMessageHandler 가
     * `Assert.notNull(taskScheduler, ...)` 으로 startup 시 IllegalArgumentException.
     */
    @Bean
    fun stompHeartbeatScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("stomp-heartbeat-")
            initialize()
        }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS()   // SockJS fallback (오래된 브라우저 / 회사 firewall WebSocket 차단 환경)
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        // simple in-memory broker. /topic = pub/sub broadcast, /queue = point-to-point
        registry.enableSimpleBroker("/topic", "/queue")
            .setHeartbeatValue(longArrayOf(10_000, 10_000))
            // 양방향 10초 heartbeat → idle / 망가진 connection 빠른 탐지
            .setTaskScheduler(stompHeartbeatScheduler())

        // 클라이언트 → 서버 메시지 (현재는 사용 안 하지만 향후 확장 대비)
        registry.setApplicationDestinationPrefixes("/app")

        // /user/{userId}/queue/... 형태의 user destination 활성화
        registry.setUserDestinationPrefix("/user")
    }
}
