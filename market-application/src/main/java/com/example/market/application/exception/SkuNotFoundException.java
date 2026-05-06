package com.example.market.application.exception;

import com.example.market.domain.catalog.SkuId;

public class SkuNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SkuNotFoundException(SkuId id) {
        super("sku not found: " + id);
    }
}
