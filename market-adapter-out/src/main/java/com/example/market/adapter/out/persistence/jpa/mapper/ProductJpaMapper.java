package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.ProductJpaEntity;
import com.example.market.domain.catalog.Product;
import com.example.market.domain.catalog.ProductId;

public final class ProductJpaMapper {

    private ProductJpaMapper() {}

    public static ProductJpaEntity toEntity(Product p) {
        ProductJpaEntity e = new ProductJpaEntity();
        e.setId(p.id().value());
        e.setBrand(p.brand());
        e.setModelName(p.modelName());
        e.setStyleCode(p.styleCode());
        e.setCategory(p.category());
        e.setReleaseDate(p.releaseDate());
        e.setImageUrl(p.imageUrl());
        e.setCreatedAt(p.createdAt());
        return e;
    }

    public static Product toDomain(ProductJpaEntity e) {
        return Product.restore(
                new ProductId(e.getId()),
                e.getBrand(), e.getModelName(), e.getStyleCode(),
                e.getCategory(), e.getReleaseDate(), e.getImageUrl(),
                e.getCreatedAt());
    }
}
