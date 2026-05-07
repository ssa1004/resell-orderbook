package com.example.market.adapter.out.persistence.jpa.repository;

import com.example.market.adapter.out.persistence.jpa.entity.PriceTickJpaEntity;

import java.util.Currency;

/**
 * 작은 헬퍼 — entity 의 currency 문자열을 Currency 객체로.
 * adapter 한 두 곳에서만 쓰는 변환이라 별도 mapper 만들기 부담스러워 여기 모음.
 */
public final class SkuLookup {

    private SkuLookup() {}

    public static Currency currencyOf(PriceTickJpaEntity e) {
        return Currency.getInstance(e.getCurrency());
    }
}
