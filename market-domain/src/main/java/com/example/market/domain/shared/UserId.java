package com.example.market.domain.shared;

import java.util.Objects;

/**
 * 사용자 식별자 — buyer/seller/inspector 모두 같은 타입.
 *
 * <p>외부 IdP (OAuth2) 의 subject(sub) 를 그대로 사용. UUID 강제 X — KREAM 의 회원번호 형식 존중.</p>
 */
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "userId");
        if (value.isBlank()) throw new IllegalArgumentException("userId must not be blank");
    }

    public static UserId of(String value) { return new UserId(value); }

    @Override public String toString() { return value; }
}
