package com.example.market.adapter.out.pg;

import com.example.market.application.port.out.PgClient;
import com.example.market.domain.shared.Money;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Currency;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resilience4j CB 시나리오 — 연속 실패 → OPEN → 다음 호출 fallback 즉시 응답.
 */
@SpringBootTest(classes = RestPgClientCircuitBreakerIT.TestApp.class)
class RestPgClientCircuitBreakerIT {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static WireMockServer wiremock;

    @BeforeAll
    static void startWiremock() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWiremock() {
        if (wiremock != null) wiremock.stop();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("market.pg.enabled", () -> "true");
        // 작은 임계치로 빨리 OPEN
        r.add("resilience4j.circuitbreaker.instances.pg.slidingWindowSize", () -> "4");
        r.add("resilience4j.circuitbreaker.instances.pg.minimumNumberOfCalls", () -> "4");
        r.add("resilience4j.circuitbreaker.instances.pg.failureRateThreshold", () -> "50");
        r.add("resilience4j.circuitbreaker.instances.pg.waitDurationInOpenState", () -> "30s");
        r.add("resilience4j.retry.instances.pg.maxAttempts", () -> "1");
    }

    @Autowired RestPgClient client;
    @Autowired CircuitBreakerRegistry cbRegistry;

    @BeforeEach
    void resetCb() {
        wiremock.resetAll();
        cbRegistry.circuitBreaker("pg").reset();
    }

    @Test
    void afterRepeatedFailures_circuitBreakerOpens_fallbackKicksIn() {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        var req = new PgClient.AuthorizeRequest(
                "trade-fail", money(150_000), "trade-fail", "buyer-x");

        // 4회 호출 → CB 가 OPEN
        for (int i = 0; i < 4; i++) {
            var r = client.authorize(req);
            assertThat(r.approved()).isFalse();
        }

        CircuitBreaker cb = cbRegistry.circuitBreaker("pg");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 다음 호출은 fallback (PG 미도달) — wiremock 으로의 새 요청 발생 X
        wiremock.resetAll();
        var fallback = client.authorize(req);
        assertThat(fallback.approved()).isFalse();
        assertThat(fallback.errorCode()).isEqualTo("CB_OPEN");
        assertThat(wiremock.getAllServeEvents()).isEmpty();
    }

    @Test
    void successfulCalls_keepCircuitClosed() {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"approved\":true,\"pgPaymentId\":\"ok-tx\",\"errorCode\":null,\"errorMessage\":null}")));

        var req = new PgClient.AuthorizeRequest(
                "trade-ok", money(500), "trade-ok", "buyer-y");
        for (int i = 0; i < 5; i++) {
            assertThat(client.authorize(req).approved()).isTrue();
        }
        assertThat(cbRegistry.circuitBreaker("pg").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            org.redisson.spring.starter.RedissonAutoConfigurationV2.class,
            org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
            org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration.class,
            org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
            org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class,
    })
    @ComponentScan(basePackages = "com.example.market.adapter.out.pg")
    static class TestApp {
        @Bean
        RestClient pgRestClient() {
            return RestClient.builder()
                    .baseUrl(wiremock.baseUrl())
                    .requestFactory(new SimpleClientHttpRequestFactory())
                    .build();
        }
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
