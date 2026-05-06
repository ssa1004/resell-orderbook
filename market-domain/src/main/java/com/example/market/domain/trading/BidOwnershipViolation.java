package com.example.market.domain.trading;

import com.example.market.domain.shared.UserId;

/**
 * 다른 사용자의 Bid 를 조작하려고 할 때.
 */
public class BidOwnershipViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BidOwnershipViolation(BidId bidId, UserId owner, UserId requestor) {
        super("bid " + bidId + " owned by " + owner + " — requestor " + requestor + " forbidden");
    }
}
