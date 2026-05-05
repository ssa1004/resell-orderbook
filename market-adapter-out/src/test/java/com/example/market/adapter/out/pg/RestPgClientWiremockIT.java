package com.example.market.adapter.out.pg;

import com.example.market.application.port.out.PgClient;
import com.example.market.domain.shared.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Currency;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RestPgClient PG contract — Wiremock 으로 PG 응답 시뮬.
 * Java 21 의 default HTTP/2 와 Wiremock Jetty 가 RST_STREAM 충돌 → SimpleClientHttpRequestFactory(HTTP/1.1).
 */
class RestPgClientWiremockIT {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final ObjectMapper json = new ObjectMapper();

    private static WireMockServer wiremock;
    private RestPgClient client;

    @BeforeAll
    static void startServer() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopServer() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void setUp() {
        wiremock.resetAll();
        var rest = RestClient.builder()
                .baseUrl(wiremock.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        client = new RestPgClient(rest);
    }

    @Test
    void authorize_success() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .withRequestBody(matchingJsonPath("$.idempotencyKey", equalTo("trade-1")))
                .withRequestBody(matchingJsonPath("$.tradeId", equalTo("trade-1")))
                .withRequestBody(matchingJsonPath("$.buyerId", equalTo("buyer-x")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(
                                PgClient.AuthorizeResult.approved("pg-tx-1")))));

        var result = client.authorize(new PgClient.AuthorizeRequest(
                "trade-1", money(150_000), "trade-1", "buyer-x"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgPaymentId()).isEqualTo("pg-tx-1");
    }

    @Test
    void authorize_pgRejected_mapsToRejectedResult() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/authorize"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(
                                PgClient.AuthorizeResult.rejected("INSUFFICIENT_FUNDS", "card declined")))));

        var result = client.authorize(new PgClient.AuthorizeRequest(
                "trade-2", money(150_000), "trade-2", "buyer-y"));

        assertThat(result.approved()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void refund_success() throws Exception {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/refund"))
                .withRequestBody(matchingJsonPath("$.pgPaymentId", equalTo("pg-tx-1")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json.writeValueAsString(
                                PgClient.RefundResult.approved("pg-refund-1")))));

        var result = client.refund(new PgClient.RefundRequest(
                "pg-tx-1", money(150_000), "fake item"));

        assertThat(result.approved()).isTrue();
        assertThat(result.pgRefundId()).isEqualTo("pg-refund-1");
    }

    @Test
    void refund_serverError_propagatesAsRuntimeException() {
        wiremock.stubFor(post(urlEqualTo("/v1/payments/refund"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        assertThatThrownBy(() -> client.refund(new PgClient.RefundRequest(
                "pg-tx-fail", money(150_000), "any")))
                .isInstanceOf(RuntimeException.class);
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
