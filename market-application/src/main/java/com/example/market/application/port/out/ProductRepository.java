package com.example.market.application.port.out;

import com.example.market.domain.catalog.Product;
import com.example.market.domain.catalog.ProductId;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    void save(Product product);
    Optional<Product> findById(ProductId id);
    List<Product> findByBrand(String brand);
}
