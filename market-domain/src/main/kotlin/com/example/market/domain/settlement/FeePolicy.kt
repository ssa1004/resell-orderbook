package com.example.market.domain.settlement

import com.example.market.domain.shared.Money
import java.math.BigDecimal

/**
 * 수수료 정책 — 한정판 리셀 마켓에서 일반적인 구성을 따른다:
 *
 * - **구매자 결제액** = 거래가 + 구매자 수수료(%) + 검수비 + 배송비
 * - **판매자 수령액** = 거래가 - 판매자 수수료(%) - 결제대행 수수료(고정)
 * - 플랫폼 수익 = 구매자 수수료 + 판매자 수수료 + 검수비 + 배송비 - 결제대행 수수료
 *
 * 예 (스니커즈 ~150,000원 거래):
 * ```
 *   buyerCommissionRate=3.5  → 구매자 수수료 = 5,250
 *   sellerCommissionRate=3.0 → 판매자 수수료 = 4,500
 *   inspectionFee=3,000 (구매자 부담)
 *   shippingFee=3,000 (구매자 부담)
 *   fixedProcessingFee=1,000 (판매자 부담)
 *
 *   buyerCharge = 150,000 + 5,250 + 3,000 + 3,000 = 161,250
 *   sellerNet   = 150,000 - 4,500 - 1,000 = 144,500
 * ```
 *
 * 모든 수수료(fee)는 같은 통화여야 함 (KRW 가정). 정책은 시점별로 바뀔 수 있으므로 매칭이
 * 일어난 시점에 [FeeSnapshot] 으로 그대로 보관 — 이후 정책이 바뀌어도 이 거래의 수수료는
 * 변하지 않는다.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor 를 노출해 호출자
 * 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class FeePolicy(
    val sellerCommissionRate: BigDecimal,
    val buyerCommissionRate: BigDecimal,
    val inspectionFee: Money,
    val shippingFee: Money,
    val fixedProcessingFee: Money,
) {
    init {
        validateRate(sellerCommissionRate, "sellerCommissionRate")
        validateRate(buyerCommissionRate, "buyerCommissionRate")
        require(!inspectionFee.isNegative) { "inspectionFee must be >= 0" }
        require(!shippingFee.isNegative) { "shippingFee must be >= 0" }
        require(!fixedProcessingFee.isNegative) { "processingFee must be >= 0" }
        val c = inspectionFee.currency()
        require(shippingFee.currency() == c && fixedProcessingFee.currency() == c) {
            "all fees must share currency"
        }
    }

    fun sellerCommissionOf(tradeAmount: Money): Money =
        tradeAmount.percentage(sellerCommissionRate)

    fun buyerCommissionOf(tradeAmount: Money): Money =
        tradeAmount.percentage(buyerCommissionRate)

    /** 구매자가 결제 게이트웨이(PG) 로 결제할 총액 = 거래가 + 구매자 수수료 + 검수비 + 배송비. */
    fun buyerChargeFor(tradeAmount: Money): Money = tradeAmount
        .add(buyerCommissionOf(tradeAmount))
        .add(inspectionFee)
        .add(shippingFee)

    /** 판매자 계좌로 송금될 실수령액 = 거래가 - 판매자 수수료 - 결제대행 수수료. */
    fun sellerNetFor(tradeAmount: Money): Money = tradeAmount
        .subtract(sellerCommissionOf(tradeAmount))
        .subtract(fixedProcessingFee)

    /** 매칭 순간의 수수료 명세를 스냅샷으로 고정 (freeze). 이후 정책이 바뀌어도 이 snapshot 은 불변. */
    fun snapshotFor(tradeAmount: Money): FeeSnapshot {
        val sellerComm = sellerCommissionOf(tradeAmount)
        val buyerComm = buyerCommissionOf(tradeAmount)
        val buyerCharge = buyerChargeFor(tradeAmount)
        val sellerNet = sellerNetFor(tradeAmount)
        require(!sellerNet.isNegative) {
            "sellerNet negative — tradeAmount $tradeAmount too small for fees"
        }
        return FeeSnapshot(
            tradeAmount, sellerCommissionRate, buyerCommissionRate,
            inspectionFee, shippingFee, fixedProcessingFee,
            sellerComm, buyerComm, buyerCharge, sellerNet,
        )
    }

    private companion object {
        private fun validateRate(r: BigDecimal, name: String) {
            require(r.signum() >= 0 && r.compareTo(BigDecimal.valueOf(50)) <= 0) {
                "$name must be in [0, 50], was $r"
            }
        }
    }
}
