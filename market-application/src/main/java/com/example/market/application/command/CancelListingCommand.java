package com.example.market.application.command;

import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.ListingId;

public record CancelListingCommand(UserId requestor, ListingId listingId) {}
