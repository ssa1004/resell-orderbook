package com.example.market.application.exception;

import com.example.market.domain.catalog.ProductId;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(ProductId id) {
        super("product not found: " + id);
    }
}
