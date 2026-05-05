package com.example.market.domain.trading;

import com.example.market.domain.shared.UserId;

/**
 * 다른 사용자의 Listing 을 조작하려고 할 때. 도메인이 *권한* 위반을 신호.
 * Application 레이어에서 catch 해서 HTTP 403 으로 매핑.
 */
public class ListingOwnershipViolation extends RuntimeException {
    public ListingOwnershipViolation(ListingId listingId, UserId owner, UserId requestor) {
        super("listing " + listingId + " owned by " + owner + " — requestor " + requestor + " forbidden");
    }
}
