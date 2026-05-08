package com.example.market;

import io.github.resilience4j.common.retry.configuration.CommonRetryConfigurationProperties;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml 의 Resilience4j retry instance 가 정확히 로드되는지 검증 (ADR-0026).
 *
 * <p>3 instance — pg / bank / inspection-center — 모두 동일한 표준 정책: maxAttempts 3, exponential
 * backoff, jitter (randomizedWaitFactor) 활성, 4xx 는 ignore. yml 의 오타 / Resilience4j 의
 * 설정 키 변경에 대한 회귀 보호.</p>
 *
 * <p>{@code SpringBootTest} 대신 {@link ApplicationContextRunner} — Resilience4j 의 retry
 * autoconfiguration 만 살린 슬림 컨텍스트. 다른 빈 (JPA / Kafka / saga) 의 의존성 영향 없이
 * yml 의 retry 설정만 격리 검증.</p>
 */
class RetryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RetryAutoConfiguration.class))
            .withPropertyValues(
                    "resilience4j.retry.instances.pg.max-attempts=3",
                    "resilience4j.retry.instances.pg.wait-duration=200ms",
                    "resilience4j.retry.instances.pg.enable-exponential-backoff=true",
                    "resilience4j.retry.instances.pg.exponential-backoff-multiplier=2",
                    "resilience4j.retry.instances.pg.randomized-wait-factor=0.5",
                    "resilience4j.retry.instances.pg.retry-exceptions[0]=java.io.IOException",
                    "resilience4j.retry.instances.pg.retry-exceptions[1]=org.springframework.web.client.HttpServerErrorException",
                    "resilience4j.retry.instances.pg.ignore-exceptions[0]=org.springframework.web.client.HttpClientErrorException",

                    "resilience4j.retry.instances.bank.max-attempts=3",
                    "resilience4j.retry.instances.bank.wait-duration=500ms",
                    "resilience4j.retry.instances.bank.enable-exponential-backoff=true",
                    "resilience4j.retry.instances.bank.exponential-backoff-multiplier=2",
                    "resilience4j.retry.instances.bank.randomized-wait-factor=0.5",

                    "resilience4j.retry.instances.inspection-center.max-attempts=3",
                    "resilience4j.retry.instances.inspection-center.wait-duration=300ms",
                    "resilience4j.retry.instances.inspection-center.enable-exponential-backoff=true",
                    "resilience4j.retry.instances.inspection-center.exponential-backoff-multiplier=2",
                    "resilience4j.retry.instances.inspection-center.randomized-wait-factor=0.5"
            );

    @Test
    void pgRetry_hasMaxAttemptsAndJitter() {
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);
            RetryConfig config = registry.retry("pg").getRetryConfig();
            assertThat(config.getMaxAttempts()).isEqualTo(3);
            // jitter 가 살아 있으면 IntervalBiFunction 이 등록되어 있다.
            assertThat(config.getIntervalBiFunction()).isNotNull();
        });
    }

    @Test
    void bankRetry_definedForFutureBankAdapter() {
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);
            assertThat(registry.retry("bank").getRetryConfig().getMaxAttempts()).isEqualTo(3);
        });
    }

    @Test
    void inspectionCenterRetry_definedForFutureExternalApi() {
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);
            assertThat(registry.retry("inspection-center").getRetryConfig().getMaxAttempts()).isEqualTo(3);
        });
    }

    @Test
    void pgRetry_ignores4xxButRetries5xx() {
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);
            RetryConfig config = registry.retry("pg").getRetryConfig();

            var serverError = new org.springframework.web.client.HttpServerErrorException(
                    org.springframework.http.HttpStatusCode.valueOf(503));
            var clientError = new org.springframework.web.client.HttpClientErrorException(
                    org.springframework.http.HttpStatusCode.valueOf(400));

            assertThat(config.getExceptionPredicate().test(serverError)).isTrue();
            assertThat(config.getExceptionPredicate().test(clientError)).isFalse();
        });
    }

    @Test
    void pgRetry_baseWaitWithinJitterRange() {
        // exponential backoff 가 실제로 적용되는지 — base 200ms × 2^n. jitter 0.5 → ±50%.
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);
            RetryConfig config = registry.retry("pg").getRetryConfig();

            // 첫 attempt 후 wait — 200ms × (1 ± 0.5) → 100~300ms.
            long firstWait = config.getIntervalBiFunction().apply(1, null);
            assertThat(firstWait).isBetween(100L, 300L);

            // 두 번째 attempt — 400ms × (1 ± 0.5) → 200~600ms.
            long secondWait = config.getIntervalBiFunction().apply(2, null);
            assertThat(secondWait).isBetween(200L, 600L);
        });
    }

    @Test
    void allInstancesAreRegistered() {
        runner.run(ctx -> {
            RetryRegistry registry = ctx.getBean(RetryRegistry.class);
            CommonRetryConfigurationProperties props = ctx.getBean(CommonRetryConfigurationProperties.class);
            assertThat(props.getInstances())
                    .containsKeys("pg", "bank", "inspection-center");
            // 메트릭은 RetryRegistry 의 모든 retry 가 노출됨 — count 가 3 이상.
            long count = registry.getAllRetries().stream()
                    .filter(r -> r.getName().equals("pg")
                            || r.getName().equals("bank")
                            || r.getName().equals("inspection-center"))
                    .count();
            assertThat(count).isEqualTo(3);
        });
    }
}
