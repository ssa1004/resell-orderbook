package com.example.market.domain.marketdata

import com.example.market.domain.catalog.SkuId
import com.example.market.domain.shared.Money
import java.time.Instant

/**
 * 한 SKU 의 *현재 시세 요약* — 운영 화면 / 사용자 대시보드 / API 응답에 표시되는 한 카드.
 *
 * 저장하지 않는 값 객체. application service 가 PriceTick 들 + OrderBook 의 best bid/ask
 * 를 합산해 만들어 반환한다 (캐시는 호출자 책임).
 *
 * **각 필드의 의미**:
 * - `lastTradePrice / lastTradeAt` — 가장 최근 체결가. 차트의 '오른쪽 끝 점'.
 * - `bestBid / bestAsk / spread` — 호가창의 가장 좋은 매수/매도 호가 + 차이. spread 가
 *   작을수록 *유동성* 좋음.
 * - `last24hVolume` — 24시간 누적 체결 건수 (수량은 한정판이라 1당 1).
 * - `last24hMin / Avg / Max` — 24시간 가격 통계. 변동성 / 시세 추세 파악.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor 를 노출해 호출자
 * 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class MarketStats(
    val skuId: SkuId,
    val asOf: Instant,
    /** null = 아직 체결 없음 */
    val lastTradePrice: Money?,
    /** null = 아직 체결 없음 */
    val lastTradeAt: Instant?,
    /** null = bid 호가 없음 */
    val bestBid: Money?,
    /** null = ask 호가 없음 */
    val bestAsk: Money?,
    /** bestAsk - bestBid. 한쪽 없으면 null */
    val spread: Money?,
    val last24hVolume: Long,
    /** null = 24h 내 거래 없음 */
    val last24hMin: Money?,
    /** null = 24h 내 거래 없음 */
    val last24hAvg: Money?,
    /** null = 24h 내 거래 없음 */
    val last24hMax: Money?,
) {
    init {
        require(last24hVolume >= 0) { "volume must be non-negative" }
    }
}
