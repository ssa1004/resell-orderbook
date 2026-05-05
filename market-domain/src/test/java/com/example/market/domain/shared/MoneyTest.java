package com.example.market.domain.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void addRequiresSameCurrency() {
        Money a = Money.of(1000, KRW);
        Money b = Money.of(10, USD);
        assertThatThrownBy(() -> a.add(b)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addSubtractMultiply() {
        Money a = Money.of(1000, KRW);
        Money b = Money.of(300, KRW);
        assertThat(a.add(b).amount()).isEqualByComparingTo("1300");
        assertThat(a.subtract(b).amount()).isEqualByComparingTo("700");
        assertThat(a.multiply(BigDecimal.valueOf(3)).amount()).isEqualByComparingTo("3000");
    }

    @Test
    void percentage_5_5_of_100000() {
        Money price = Money.of(100_000, KRW);
        // KREAM 거래 수수료 5.5%
        Money fee = price.percentage(new BigDecimal("5.5"));
        assertThat(fee.amount()).isEqualByComparingTo("5500");
    }

    @Test
    void compareTo() {
        assertThat(Money.of(100, KRW)).isLessThan(Money.of(200, KRW));
        assertThat(Money.of(100, KRW)).isEqualByComparingTo(Money.of(100, KRW));
    }

    @Test
    void zeroAndSign() {
        assertThat(Money.zero(KRW).isZero()).isTrue();
        assertThat(Money.of(1, KRW).isPositive()).isTrue();
        assertThat(Money.of(-1, KRW).isNegative()).isTrue();
    }

    @Test
    void equality_byAmountAndCurrency() {
        assertThat(Money.of("100.0", KRW)).isEqualTo(Money.of("100", KRW));
        assertThat(Money.of(100, KRW)).isNotEqualTo(Money.of(100, USD));
    }
}
