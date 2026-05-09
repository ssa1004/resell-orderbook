package com.example.market.domain.settlement;

import com.example.market.domain.shared.Money;

import java.math.BigDecimal;

/**
 * 거래가 성사된 순간의 수수료 계산서를 그대로 고정한 (freeze) 명세. Trade 애그리거트가 같이
 * 들고 다닌다 — 정책이 나중에 바뀌어도 과거 거래의 수수료/정산액은 이 snapshot 그대로 유지.
 *
 * <p>구매자 결제액, 판매자 정산액, 플랫폼 수익 모두 이 snapshot 의 값으로 결정된다.</p>
 */
public record FeeSnapshot(
        Money tradeAmount,                 // 체결가 (구매자 BID 와 판매자 ASK 가 만난 가격)
        BigDecimal sellerCommissionRate,
        BigDecimal buyerCommissionRate,
        Money inspectionFee,
        Money shippingFee,
        Money fixedProcessingFee,
        Money sellerCommission,            // 위 비율을 체결가에 곱해 계산한 수수료
        Money buyerCommission,
        Money buyerCharge,                 // 구매자가 결제 게이트웨이(PG) 로 결제할 총액 (체결가 + 구매자 부담분)
        Money sellerNet                    // 판매자에게 실제 송금되는 금액 (체결가 - 판매자 부담분)
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
