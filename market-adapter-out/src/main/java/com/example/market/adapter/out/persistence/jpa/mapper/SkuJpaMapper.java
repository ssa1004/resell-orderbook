package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.SkuJpaEntity;
import com.example.market.domain.catalog.ProductId;
import com.example.market.domain.catalog.Sku;
import com.example.market.domain.catalog.SkuId;

public final class SkuJpaMapper {

    private SkuJpaMapper() {}

    public static SkuJpaEntity toEntity(Sku s) {
        SkuJpaEntity e = new SkuJpaEntity();
        e.setId(s.id().value());
        e.setProductId(s.productId().value());
        e.setSize(s.size());
        e.setVariant(s.variant());
        return e;
    }

    public static Sku toDomain(SkuJpaEntity e) {
        return Sku.restore(new SkuId(e.getId()), new ProductId(e.getProductId()),
                e.getSize(), e.getVariant());
    }
}
