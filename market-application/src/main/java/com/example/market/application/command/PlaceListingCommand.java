package com.example.market.application.command;

import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;

public record PlaceListingCommand(
        String idempotencyKey,
        UserId sellerId,
        SkuId skuId,
        Money askPrice
) {}
