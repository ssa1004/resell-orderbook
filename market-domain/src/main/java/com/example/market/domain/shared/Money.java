package com.example.market.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 통화-aware 금액 VO. 같은 통화끼리만 산술 가능. 음수 허용 (수수료 차감 등).
 *
 * <p>Wallet/잔액 invariant 는 도메인에서 강제 — Money 자체는 산술 단위.</p>
 */
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(long amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money percentage(BigDecimal pct) {
        // pct: 5.5 → 5.5%
        return new Money(amount.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP), currency);
    }

    public boolean isPositive() { return amount.signum() > 0; }
    public boolean isNegative() { return amount.signum() < 0; }
    public boolean isZero() { return amount.signum() == 0; }

    public BigDecimal amount() { return amount; }
    public Currency currency() { return currency; }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money o) {
        requireSameCurrency(o);
        return amount.compareTo(o.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return amount.compareTo(m.amount) == 0 && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() { return amount + " " + currency.getCurrencyCode(); }
}
