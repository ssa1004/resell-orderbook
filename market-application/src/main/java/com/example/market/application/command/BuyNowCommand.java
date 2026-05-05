package com.example.market.application.command;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.UserId;

public record BuyNowCommand(
        String idempotencyKey,
        UserId buyerId,
        SkuId skuId
) {}
