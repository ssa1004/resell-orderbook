package com.example.market.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveLoggingTest {

    @Test
    void mask_short_value_only_shows_length() {
        assertThat(SensitiveLogging.mask("ABC")).isEqualTo("***(3)");
        assertThat(SensitiveLogging.mask("ABCD")).isEqualTo("***(4)");
        assertThat(SensitiveLogging.mask(null)).isEqualTo("null");
    }

    @Test
    void mask_long_value_keeps_first_four_chars() {
        assertThat(SensitiveLogging.mask("AB1234567890")).isEqualTo("AB12***(12)");
    }

    @Test
    void maskAmount_shows_only_digit_count() {
        assertThat(SensitiveLogging.maskAmount(new BigDecimal("1234500"))).isEqualTo("***(7-digit)");
        assertThat(SensitiveLogging.maskAmount(new BigDecimal("99"))).isEqualTo("***(2-digit)");
    }

    @Test
    void maskAmount_zero_and_trailing_zeros_use_integer_digit_count() {
        assertThat(SensitiveLogging.maskAmount(BigDecimal.ZERO)).isEqualTo("***(1-digit)");
        assertThat(SensitiveLogging.maskAmount(new BigDecimal("1000"))).isEqualTo("***(4-digit)");
        // 소수부는 자릿수 계산에 포함하지 않음
        assertThat(SensitiveLogging.maskAmount(new BigDecimal("123.45"))).isEqualTo("***(3-digit)");
    }

    @Test
    void maskAmount_negative_keeps_sign() {
        assertThat(SensitiveLogging.maskAmount(new BigDecimal("-12345"))).isEqualTo("-***(5-digit)");
    }

    @Test
    void maskAmount_null_returns_null_literal() {
        assertThat(SensitiveLogging.maskAmount(null)).isEqualTo("null");
    }
}
