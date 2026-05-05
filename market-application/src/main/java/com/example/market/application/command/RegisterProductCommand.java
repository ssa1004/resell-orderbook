package com.example.market.application.command;

import com.example.market.domain.catalog.ProductCategory;

import java.time.Instant;

public record RegisterProductCommand(
        String brand,
        String modelName,
        String styleCode,
        ProductCategory category,
        Instant releaseDate,
        String imageUrl
) {}
