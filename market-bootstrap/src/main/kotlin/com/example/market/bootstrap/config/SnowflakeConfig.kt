package com.example.market.bootstrap.config

import com.example.market.domain.shared.SnowflakeIdGenerator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Clock

/**
 * Snowflake ID 생성기 Bean (ADR-0018).
 *
 * **Machine ID 결정 우선순위**:
 * 1. `MACHINE_ID` 환경변수 (운영에서 명시 권장 — pod ordinal 등)
 * 2. `market.snowflake.machine-id` 프로퍼티
 * 3. hostname 의 hash mod 1024 (자동 — K8s pod 이름의 ordinal suffix 가 hostname 에 포함)
 * 4. 위 셋 다 실패하면 0 (단일 인스턴스 dev 용)
 *
 * **StatefulSet 권장**: 운영에서는 K8s StatefulSet 으로 pod-0, pod-1, ... 같은 안정 hostname
 * 을 부여하면 hostname hash 자동 결정도 충돌 가능성이 낮다 (1024 슬롯 대비 보통 인스턴스가 수십 대).
 * 더 엄격히 보장하려면 init-container 에서 ZooKeeper / Redis 의 분산 카운터를 받아 env 로 주입.
 */
@Configuration
open class SnowflakeConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    open fun priceTickIdGenerator(
        @Value("\${market.snowflake.machine-id:#{null}}") configuredMachineId: Long?,
        clock: Clock,
    ): SnowflakeIdGenerator {
        val machineId = resolveMachineId(configuredMachineId)
        log.info("SnowflakeIdGenerator 초기화 — machineId={} (max=1023)", machineId)
        return SnowflakeIdGenerator(machineId, clock)
    }

    private fun resolveMachineId(configured: Long?): Long {
        if (configured != null) {
            require(configured in 0..SnowflakeIdGenerator.MAX_MACHINE_ID.toLong()) {
                "market.snowflake.machine-id 가 0~${SnowflakeIdGenerator.MAX_MACHINE_ID} 범위를 벗어남: $configured"
            }
            return configured
        }
        val envValue = System.getenv("MACHINE_ID")
        if (!envValue.isNullOrBlank()) {
            try {
                val parsed = envValue.trim().toLong()
                if (parsed < 0 || parsed > SnowflakeIdGenerator.MAX_MACHINE_ID) {
                    log.warn("MACHINE_ID env 가 범위를 벗어나 hostname hash fallback: {}", envValue)
                } else {
                    return parsed
                }
            } catch (e: NumberFormatException) {
                log.warn("MACHINE_ID env 파싱 실패 — hostname hash fallback: {}", envValue)
            }
        }
        // hostname hash — K8s StatefulSet 의 안정 pod 이름이 들어오면 충분히 균등 분포.
        return try {
            val hostname = InetAddress.getLocalHost().hostName
            val hash = hostname.hashCode()
            // 음수 보정 + 1024 mod
            ((hash and Int.MAX_VALUE) % (SnowflakeIdGenerator.MAX_MACHINE_ID + 1)).toLong()
        } catch (e: UnknownHostException) {
            log.warn("hostname 조회 실패 — machineId=0 (dev 단일 인스턴스 가정)")
            0L
        }
    }
}
