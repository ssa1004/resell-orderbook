package com.example.market.domain.settlement

import com.example.market.domain.shared.Money
import java.math.BigDecimal

/**
 * 거래가 성사된 순간의 수수료 계산서를 그대로 고정한 (freeze) 명세. Trade 애그리거트가 같이
 * 들고 다닌다 — 정책이 나중에 바뀌어도 과거 거래의 수수료/정산액은 이 snapshot 그대로 유지.
 *
 * 구매자 결제액, 판매자 정산액, 플랫폼 수익 모두 이 snapshot 의 값으로 결정된다.
 *
 * Kotlin `@JvmRecord` 로 컴파일 — Java record 와 동일한 component accessor 를 노출해 호출자
 * 호환성 (Java + Kotlin) 보존.
 */
@JvmRecord
data class FeeSnapshot(
    /** 체결가 (구매자 BID 와 판매자 ASK 가 만난 가격) */
    val tradeAmount: Money,
    val sellerCommissionRate: BigDecimal,
    val buyerCommissionRate: BigDecimal,
    val inspectionFee: Money,
    val shippingFee: Money,
    val fixedProcessingFee: Money,
    /** 위 비율을 체결가에 곱해 계산한 수수료 */
    val sellerCommission: Money,
    val buyerCommission: Money,
    /** 구매자가 결제 게이트웨이(PG) 로 결제할 총액 (체결가 + 구매자 부담분) */
    val buyerCharge: Money,
    /** 판매자에게 실제 송금되는 금액 (체결가 - 판매자 부담분) */
    val sellerNet: Money,
) {
    /** 플랫폼이 거래 1건에서 가져가는 수익. */
    fun platformRevenue(): Money = buyerCommission
        .add(sellerCommission)
        .add(inspectionFee)
        .add(shippingFee)
        .subtract(fixedProcessingFee)
}
