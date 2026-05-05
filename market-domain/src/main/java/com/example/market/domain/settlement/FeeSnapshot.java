package com.example.market.domain.settlement;

import com.example.market.domain.shared.Money;

import java.math.BigDecimal;

/**
 * 거래 시점에 freeze 된 수수료 명세. Trade aggregate 가 보유 — 정책이 나중에 바뀌어도
 * 과거 거래의 수수료/정산액은 변하지 않음.
 *
 * <p>구매자 결제액 / 판매자 정산액 / 플랫폼 수익 모두 이 snapshot 으로 결정.</p>
 */
public record FeeSnapshot(
        Money tradeAmount,                 // 체결가
        BigDecimal sellerCommissionRate,
        BigDecimal buyerCommissionRate,
        Money inspectionFee,
        Money shippingFee,
        Money fixedProcessingFee,
        Money sellerCommission,            // 계산 결과
        Money buyerCommission,
        Money buyerCharge,                 // 구매자가 PG 에 결제할 총액
        Money sellerNet                    // 판매자 송금액
) {
    /** 플랫폼이 거래 1건에서 가져가는 수익. */
    public Money platformRevenue() {
        return buyerCommission
                .add(sellerCommission)
                .add(inspectionFee)
                .add(shippingFee)
                .subtract(fixedProcessingFee);
    }
}
