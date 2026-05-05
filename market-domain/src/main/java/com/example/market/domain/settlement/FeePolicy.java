package com.example.market.domain.settlement;

import com.example.market.domain.shared.Money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 수수료 정책 — KREAM 모델 충실:
 *
 * <ul>
 *   <li><strong>구매자 결제액</strong> = 거래가 + buyerCommission(%) + 검수비 + 배송비</li>
 *   <li><strong>판매자 수령액</strong> = 거래가 - sellerCommission(%) - 결제대행수수료(고정)</li>
 *   <li>플랫폼 수익 = buyerCommission + sellerCommission + inspectionFee + shippingFee - 결제대행수수료</li>
 * </ul>
 *
 * <p>예 (KREAM 신발 ~150,000원 거래):</p>
 * <pre>
 *   buyerCommissionRate=3.5  → buyerCommission = 5,250
 *   sellerCommissionRate=3.0 → sellerCommission = 4,500
 *   inspectionFee=3,000 (구매자 부담)
 *   shippingFee=3,000 (구매자 부담)
 *   fixedProcessingFee=1,000 (판매자 부담)
 *
 *   buyerCharge = 150,000 + 5,250 + 3,000 + 3,000 = 161,250
 *   sellerNet   = 150,000 - 4,500 - 1,000 = 144,500
 * </pre>
 *
 * <p>모든 fee 가 같은 통화여야 함 (KRW 가정). 정책은 시점별로 바뀔 수 있어 거래 시점에
 * {@link FeeSnapshot} 으로 고정.</p>
 */
public record FeePolicy(
        BigDecimal sellerCommissionRate,
        BigDecimal buyerCommissionRate,
        Money inspectionFee,
        Money shippingFee,
        Money fixedProcessingFee
) {

    public FeePolicy {
        validateRate(sellerCommissionRate, "sellerCommissionRate");
        validateRate(buyerCommissionRate, "buyerCommissionRate");
        Objects.requireNonNull(inspectionFee, "inspectionFee");
        Objects.requireNonNull(shippingFee, "shippingFee");
        Objects.requireNonNull(fixedProcessingFee, "fixedProcessingFee");
        if (inspectionFee.isNegative()) throw new IllegalArgumentException("inspectionFee must be >= 0");
        if (shippingFee.isNegative()) throw new IllegalArgumentException("shippingFee must be >= 0");
        if (fixedProcessingFee.isNegative()) throw new IllegalArgumentException("processingFee must be >= 0");
        Currency c = inspectionFee.currency();
        if (!shippingFee.currency().equals(c) || !fixedProcessingFee.currency().equals(c)) {
            throw new IllegalArgumentException("all fees must share currency");
        }
    }

    private static void validateRate(BigDecimal r, String name) {
        Objects.requireNonNull(r, name);
        if (r.signum() < 0 || r.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw new IllegalArgumentException(name + " must be in [0, 50], was " + r);
        }
    }

    public Money sellerCommissionOf(Money tradeAmount) {
        return tradeAmount.percentage(sellerCommissionRate);
    }

    public Money buyerCommissionOf(Money tradeAmount) {
        return tradeAmount.percentage(buyerCommissionRate);
    }

    /** 구매자가 PG 에 결제할 총액. */
    public Money buyerChargeFor(Money tradeAmount) {
        return tradeAmount
                .add(buyerCommissionOf(tradeAmount))
                .add(inspectionFee)
                .add(shippingFee);
    }

    /** 판매자에게 송금될 정산액. */
    public Money sellerNetFor(Money tradeAmount) {
        return tradeAmount
                .subtract(sellerCommissionOf(tradeAmount))
                .subtract(fixedProcessingFee);
    }

    /** 거래 시점에 정책을 freeze. */
    public FeeSnapshot snapshotFor(Money tradeAmount) {
        Money sellerComm = sellerCommissionOf(tradeAmount);
        Money buyerComm = buyerCommissionOf(tradeAmount);
        Money buyerCharge = buyerChargeFor(tradeAmount);
        Money sellerNet = sellerNetFor(tradeAmount);
        if (sellerNet.isNegative()) {
            throw new IllegalArgumentException(
                    "sellerNet negative — tradeAmount " + tradeAmount + " too small for fees");
        }
        return new FeeSnapshot(
                tradeAmount, sellerCommissionRate, buyerCommissionRate,
                inspectionFee, shippingFee, fixedProcessingFee,
                sellerComm, buyerComm, buyerCharge, sellerNet);
    }
}
