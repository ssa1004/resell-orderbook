package com.example.market.domain.shared

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Objects

/**
 * 통화-aware 금액 VO. 같은 통화끼리만 산술 가능. 음수 허용 (수수료 차감 등).
 *
 * Wallet / 잔액 invariant 는 도메인에서 강제 — Money 자체는 산술 단위.
 *
 * data class 가 아닌 일반 class — equals/hashCode 가 BigDecimal scale 무시 (`compareTo == 0`
 * + `stripTrailingZeros`) 로 정의되어 있어 자동 생성으로는 호환 불가.
 *
 * `@get:JvmName("amount" / "currency")` 로 기존 Java record-style accessor 보존.
 */
class Money private constructor(
    @get:JvmName("amount") val amount: BigDecimal,
    @get:JvmName("currency") val currency: Currency,
) : Comparable<Money> {

    fun add(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.add(other.amount), currency)
    }

    fun subtract(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount.subtract(other.amount), currency)
    }

    fun multiply(factor: BigDecimal): Money = Money(amount.multiply(factor), currency)

    /** pct: 5.5 → 5.5% */
    fun percentage(pct: BigDecimal): Money = Money(
        amount.multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP),
        currency,
    )

    @get:JvmName("isPositive")
    val isPositive: Boolean get() = amount.signum() > 0

    @get:JvmName("isNegative")
    val isNegative: Boolean get() = amount.signum() < 0

    @get:JvmName("isZero")
    val isZero: Boolean get() = amount.signum() == 0

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "currency mismatch: $currency vs ${other.currency}"
        }
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return amount.compareTo(other.amount) == 0 && currency == other.currency
    }

    override fun hashCode(): Int = Objects.hash(amount.stripTrailingZeros(), currency)

    override fun toString(): String = "$amount ${currency.currencyCode}"

    companion object {
        @JvmStatic
        fun of(amount: BigDecimal, currency: Currency): Money = Money(amount, currency)

        @JvmStatic
        fun of(amount: Long, currency: Currency): Money = Money(BigDecimal.valueOf(amount), currency)

        @JvmStatic
        fun of(amount: String, currency: Currency): Money = Money(BigDecimal(amount), currency)

        @JvmStatic
        fun zero(currency: Currency): Money = Money(BigDecimal.ZERO, currency)
    }
}
