package com.example.market.bootstrap.config;

import com.example.market.domain.shared.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;

/**
 * Snowflake ID 생성기 Bean (ADR-0018).
 *
 * <p><b>Machine ID 결정 우선순위</b>:</p>
 * <ol>
 *   <li>{@code MACHINE_ID} 환경변수 (운영에서 명시 권장 — pod ordinal 등)</li>
 *   <li>{@code market.snowflake.machine-id} 프로퍼티</li>
 *   <li>hostname 의 hash mod 1024 (자동 — K8s pod 이름의 ordinal suffix 가 hostname 에 포함)</li>
 *   <li>위 셋 다 실패하면 0 (단일 인스턴스 dev 용)</li>
 * </ol>
 *
 * <p><b>StatefulSet 권장</b>: 운영에서는 K8s StatefulSet 으로 pod-0, pod-1, ... 같은 안정 hostname
 * 을 부여하면 hostname hash 자동 결정도 충돌 가능성이 낮다 (1024 슬롯 대비 보통 인스턴스가 수십 대).
 * 더 엄격히 보장하려면 init-container 에서 ZooKeeper / Redis 의 분산 카운터를 받아 env 로 주입.</p>
 */
@Configuration
@Slf4j
public class SnowflakeConfig {

    @Bean
    public SnowflakeIdGenerator priceTickIdGenerator(
            @Value("${market.snowflake.machine-id:#{null}}") Long configuredMachineId,
            Clock clock) {
        long machineId = resolveMachineId(configuredMachineId);
        log.info("SnowflakeIdGenerator 초기화 — machineId={} (max=1023)", machineId);
        return new SnowflakeIdGenerator(machineId, clock);
    }

    private long resolveMachineId(Long configured) {
        if (configured != null) {
            if (configured < 0 || configured > SnowflakeIdGenerator.MAX_MACHINE_ID) {
                throw new IllegalArgumentException(
                        "market.snowflake.machine-id 가 0~" + SnowflakeIdGenerator.MAX_MACHINE_ID
                                + " 범위를 벗어남: " + configured);
            }
            return configured;
        }
        String envValue = System.getenv("MACHINE_ID");
        if (envValue != null && !envValue.isBlank()) {
            try {
                long parsed = Long.parseLong(envValue.trim());
                if (parsed < 0 || parsed > SnowflakeIdGenerator.MAX_MACHINE_ID) {
                    log.warn("MACHINE_ID env 가 범위를 벗어나 hostname hash fallback: {}", envValue);
                } else {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("MACHINE_ID env 파싱 실패 — hostname hash fallback: {}", envValue);
            }
        }
        // hostname hash — K8s StatefulSet 의 안정 pod 이름이 들어오면 충분히 균등 분포.
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            int hash = hostname.hashCode();
            // 음수 보정 + 1024 mod
            return (hash & Integer.MAX_VALUE) % (SnowflakeIdGenerator.MAX_MACHINE_ID + 1);
        } catch (UnknownHostException e) {
            log.warn("hostname 조회 실패 — machineId=0 (dev 단일 인스턴스 가정)");
            return 0L;
        }
    }
}
