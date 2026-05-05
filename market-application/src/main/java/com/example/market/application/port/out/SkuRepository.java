package com.example.market.application.port.out;

import com.example.market.domain.catalog.ProductId;
import com.example.market.domain.catalog.Sku;
import com.example.market.domain.catalog.SkuId;

import java.util.List;
import java.util.Optional;

public interface SkuRepository {
    void save(Sku sku);
    Optional<Sku> findById(SkuId id);
    List<Sku> findByProductId(ProductId productId);
}
