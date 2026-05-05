package com.example.market.adapter.out.pg;

import com.example.market.application.port.out.PgClient;
import com.example.market.domain.shared.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class MockPgClientTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private final MockPgClient client = new MockPgClient();

    @Test
    void authorize_normalKey_approved() {
        var result = client.authorize(new PgClient.AuthorizeRequest(
                "trade-1", money(150_000), "trade-1", "buyer-1"));
        assertThat(result.approved()).isTrue();
        assertThat(result.pgPaymentId()).startsWith("mock-pg-");
    }

    @Test
    void authorize_failPrefix_rejected() {
        var result = client.authorize(new PgClient.AuthorizeRequest(
                "FAIL_trade-1", money(150_000), "trade-1", "buyer-1"));
        assertThat(result.approved()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MOCK_FAIL");
    }

    @Test
    void refund_normalKey_approved() {
        var result = client.refund(new PgClient.RefundRequest(
                "mock-pg-xxx", money(150_000), "fake item"));
        assertThat(result.approved()).isTrue();
        assertThat(result.pgRefundId()).startsWith("mock-refund-");
    }

    @Test
    void refund_failPrefix_rejected() {
        var result = client.refund(new PgClient.RefundRequest(
                "FAIL_pg-xxx", money(150_000), "any"));
        assertThat(result.approved()).isFalse();
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
