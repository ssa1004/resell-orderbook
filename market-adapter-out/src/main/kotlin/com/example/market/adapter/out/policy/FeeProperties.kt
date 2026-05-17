package com.example.market.adapter.out.policy

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * application.yml 의 `market.fee.*` 매핑.
 *
 * ```
 * market:
 *   fee:
 *     currency: KRW
 *     seller-commission-rate: 3.0
 *     buyer-commission-rate: 3.5
 *     inspection-fee: 3000
 *     shipping-fee: 3000
 *     fixed-processing-fee: 1000
 * ```
 */
@ConfigurationProperties(prefix = "market.fee")
@JvmRecord
data class FeeProperties(
    val currency: String,
    val sellerCommissionRate: Double,
    val buyerCommissionRate: Double,
    val inspectionFee: Long,
    val shippingFee: Long,
    val fixedProcessingFee: Long,
)
