package com.example.market.application.exception;

import com.example.market.domain.catalog.SkuId;

public class SkuNotFoundException extends RuntimeException {
    public SkuNotFoundException(SkuId id) {
        super("sku not found: " + id);
    }
}
