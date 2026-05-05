package com.example.market.application.command;

import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.BidId;

public record CancelBidCommand(UserId requestor, BidId bidId) {}
