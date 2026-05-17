package com.example.market.adapter.out.bulkhead

import com.example.market.application.port.out.BankTransferClient
import com.example.market.application.port.out.PgClient
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * 외부 호출 격리 풀 (ThreadPoolBulkhead) 빈 등록 (ADR-0021).
 *
 * `market.bulkhead.enabled=true` 일 때만 활성. raw 어댑터 빈은 `"rawPgClient"` /
 * `"rawBankTransferClient"` 라는 별 이름 (Qualifier) 으로 등록되어 있고 — 이 Configuration
 * 이 그것을 데코레이터로 감싼 빈을 [Primary] 로 노출. application 측은 인터페이스로
 * 주입받을 때 항상 격리된 버전이 우선 선택된다.
 *
 * raw 어댑터에 직접 접근이 필요한 경우 (테스트 / 운영 도구) 는 `@Qualifier("rawPgClient")`
 * 로 우회 가능 — 격리 정책이 한 곳 (이 Configuration) 에 모인다.
 */
@Configuration
@EnableConfigurationProperties(BulkheadProperties::class)
@ConditionalOnProperty(name = ["market.bulkhead.enabled"], havingValue = "true")
class BulkheadConfiguration {

    @Bean
    fun threadPoolBulkheadRegistry(): ThreadPoolBulkheadRegistry =
        ThreadPoolBulkheadRegistry.ofDefaults()

    @Bean
    fun externalCallBulkheads(
        props: BulkheadProperties,
        registry: ThreadPoolBulkheadRegistry,
    ): ExternalCallBulkheads = ExternalCallBulkheads(props, registry)

    @Bean
    @Primary
    fun bulkheadedPgClient(
        @Qualifier("rawPgClient") delegate: PgClient,
        bulkheads: ExternalCallBulkheads,
    ): PgClient = BulkheadedPgClient(delegate, bulkheads.of("pg"))

    @Bean
    @Primary
    fun bulkheadedBankTransferClient(
        @Qualifier("rawBankTransferClient") delegate: BankTransferClient,
        bulkheads: ExternalCallBulkheads,
    ): BankTransferClient = BulkheadedBankTransferClient(delegate, bulkheads.of("bank"))
}
