package com.example.market.adapter.out.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 {@code market.fee.*} 매핑.
 *
 * <pre>
 * market:
 *   fee:
 *     currency: KRW
 *     seller-commission-rate: 3.0
 *     buyer-commission-rate: 3.5
 *     inspection-fee: 3000
 *     shipping-fee: 3000
 *     fixed-processing-fee: 1000
 * </pre>
 */
@ConfigurationProperties(prefix = "market.fee")
public record FeeProperties(
        String currency,
        double sellerCommissionRate,
        double buyerCommissionRate,
        long inspectionFee,
        long shippingFee,
        long fixedProcessingFee
) {}
