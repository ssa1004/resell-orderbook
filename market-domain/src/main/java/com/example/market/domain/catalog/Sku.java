package com.example.market.domain.catalog;

import java.util.Objects;

/**
 * Sku — Product 의 *판매 단위*. (size + variant) 조합. 거래는 Sku 단위.
 *
 * <p>예: Air Jordan 1 Chicago + size="270" + variant="Black"</p>
 */
public record Sku(SkuId id, ProductId productId, String size, String variant) {

    public Sku {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(size, "size");
        if (size.isBlank()) throw new IllegalArgumentException("size must not be blank");
        // variant 는 nullable 허용 (시계처럼 사이즈만 있는 경우)
    }

    public static Sku create(ProductId productId, String size, String variant) {
        return new Sku(SkuId.newId(), productId, size, variant);
    }

    public static Sku restore(SkuId id, ProductId productId, String size, String variant) {
        return new Sku(id, productId, size, variant);
    }
}
