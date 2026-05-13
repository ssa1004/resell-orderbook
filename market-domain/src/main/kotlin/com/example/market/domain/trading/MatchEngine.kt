package com.example.market.domain.trading

import com.example.market.domain.settlement.FeePolicy
import com.example.market.domain.shared.Money
import com.example.market.domain.shared.UserId
import java.time.Instant
import java.util.Optional

/**
 * 매칭 엔진 — 도메인 서비스. *순수 함수* (입력만으로 결과가 결정되고, DB/락/외부 호출 같은
 * 부수 효과가 없음).
 *
 * 호출자 (PlaceListingService 등) 가 같은 SkuId 의 상대편 best 호가를 한 트랜잭션 안에서
 * 잠그면서 (DB FOR UPDATE 또는 분산 락) 읽어와 이 함수에 넘긴다. 매칭 조건이 만족되면 Trade 가
 * 반환되고, 호출자는 Listing/Bid 의 상태를 MATCHED 로 마킹한 뒤 같은 트랜잭션에서 함께 커밋한다.
 *
 * 체결가 결정 (taker/maker 모델 — 시장가 주문 = taker 가 호가창에 미리 들어와 있던 = maker
 * 의 가격으로 체결):
 * - 새 ASK 가 기존 BID 와 매칭 → 가격 = 기존 **BID** 의 가격 (BID 가 maker)
 * - 새 BID 가 기존 ASK 와 매칭 → 가격 = 기존 **ASK** 의 가격 (ASK 가 maker)
 *
 * 매칭 차단 조건: 둘 다 [Listing.isMatchableAt] / [Bid.isMatchableAt] 를 통과해야 함
 * (status=ACTIVE + 만료 전). 상태만 ACTIVE 인 채로 만료 시각이 지난 호가가 동시 매칭 경쟁
 * 구간 (race window) 에서 잘못 잡히지 않도록 차단.
 *
 * Kotlin object 로 전환 — Java 에서는 정적 메서드처럼 호출 (MatchEngine.matchNewAsk(...))
 * 가능하도록 모든 메서드에 @JvmStatic 부여.
 */
object MatchEngine {

    /**
     * 새 ASK 가 들어왔을 때, 기존 BID(Highest) 와 매칭 가능한지 검사.
     *
     * @param newListing 방금 등록 시도하는 ASK
     * @param highestBid 같은 Sku 의 현재 Highest BID (없으면 empty)
     * @param feePolicy  현재 정책 — 매칭 시 snapshot 됨
     * @param now        현재 시각
     * @return 매칭 성공 시 새 Trade, 매칭 불가 시 empty
     */
    @JvmStatic
    fun matchNewAsk(
        newListing: Listing,
        highestBid: Optional<Bid>,
        feePolicy: FeePolicy,
        now: Instant,
    ): Optional<Trade> {
        if (highestBid.isEmpty) return Optional.empty()
        val bid = highestBid.get()
        if (!sameSku(newListing, bid)) return Optional.empty()
        if (!newListing.isMatchableAt(now) || !bid.isMatchableAt(now)) return Optional.empty()
        if (bid.bidPrice.compareTo(newListing.askPrice) < 0) return Optional.empty()
        if (bid.buyerId == newListing.sellerId) return Optional.empty()
        val executionPrice: Money = bid.bidPrice  // BID 가 maker
        return Optional.of(Trade.match(newListing, bid, executionPrice, feePolicy, now))
    }

    /** 새 BID 가 들어왔을 때, 기존 ASK(Lowest) 와 매칭 가능한지 검사. */
    @JvmStatic
    fun matchNewBid(
        newBid: Bid,
        lowestAsk: Optional<Listing>,
        feePolicy: FeePolicy,
        now: Instant,
    ): Optional<Trade> {
        if (lowestAsk.isEmpty) return Optional.empty()
        val ask = lowestAsk.get()
        if (!sameSku(ask, newBid)) return Optional.empty()
        if (!ask.isMatchableAt(now) || !newBid.isMatchableAt(now)) return Optional.empty()
        if (ask.askPrice.compareTo(newBid.bidPrice) > 0) return Optional.empty()
        if (ask.sellerId == newBid.buyerId) return Optional.empty()
        val executionPrice: Money = ask.askPrice  // ASK 가 maker
        return Optional.of(Trade.match(ask, newBid, executionPrice, feePolicy, now))
    }

    /**
     * BuyNow — 즉시 구매. 사용자가 구매 호가(BID)를 등록하지 않고, 호가창에 가장 낮은 가격으로
     * 올라온 판매 호가(ASK)를 그대로 매수.
     */
    @JvmStatic
    fun buyNow(lowestAsk: Listing?, buyerId: UserId, feePolicy: FeePolicy, now: Instant): Trade {
        if (lowestAsk == null || !lowestAsk.isMatchableAt(now)) {
            throw IllegalStateException("no matchable ASK to buy")
        }
        require(lowestAsk.sellerId != buyerId) { "cannot buy own listing" }
        val syntheticBid = Bid.place(lowestAsk.skuId, buyerId, lowestAsk.askPrice, now)
        return Trade.match(lowestAsk, syntheticBid, lowestAsk.askPrice, feePolicy, now)
    }

    /**
     * SellNow — 즉시 판매. 사용자가 판매 호가(ASK)를 등록하지 않고, 호가창에 가장 높은 가격으로
     * 올라온 구매 호가(BID)에 그대로 매도.
     */
    @JvmStatic
    fun sellNow(highestBid: Bid?, sellerId: UserId, feePolicy: FeePolicy, now: Instant): Trade {
        if (highestBid == null || !highestBid.isMatchableAt(now)) {
            throw IllegalStateException("no matchable BID to sell to")
        }
        require(highestBid.buyerId != sellerId) { "cannot sell to own bid" }
        val syntheticListing = Listing.place(highestBid.skuId, sellerId, highestBid.bidPrice, now)
        return Trade.match(syntheticListing, highestBid, highestBid.bidPrice, feePolicy, now)
    }

    private fun sameSku(l: Listing, b: Bid): Boolean = l.skuId == b.skuId
}
