package com.example.market.domain.trading

import com.example.market.domain.shared.UserId

/**
 * 다른 사용자의 Bid 를 조작하려고 할 때.
 */
class BidOwnershipViolation(
    bidId: BidId,
    owner: UserId,
    requestor: UserId,
) : RuntimeException(
    "bid $bidId owned by $owner — requestor $requestor forbidden",
) {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
