package com.example.market.domain.shared;

import java.util.Objects;

/**
 * 사용자 식별자 — buyer/seller/inspector 모두 같은 타입.
 *
 * <p>외부 IdP (OAuth2) 의 subject(sub) 를 그대로 사용. UUID 강제 안 함 — 외부 IdP 가 발급하는
 * 회원번호 형식 (숫자, 영숫자, ULID 등) 을 그대로 받아들인다.</p>
 */
public record UserId(String value) {

    public UserId {
        Objects.requireNonNull(value, "userId");
        if (value.isBlank()) throw new IllegalArgumentException("userId must not be blank");
    }

    public static UserId of(String value) { return new UserId(value); }

    @Override public String toString() { return value; }
}
