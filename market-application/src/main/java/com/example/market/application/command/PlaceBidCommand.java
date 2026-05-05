package com.example.market.application.command;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;

public record PlaceBidCommand(
        String idempotencyKey,
        UserId buyerId,
        SkuId skuId,
        Money bidPrice
) {}
