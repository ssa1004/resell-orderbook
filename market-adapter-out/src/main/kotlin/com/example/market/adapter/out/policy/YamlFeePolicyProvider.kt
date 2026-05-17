package com.example.market.adapter.out.policy

import com.example.market.application.port.out.FeePolicyProvider
import com.example.market.domain.settlement.FeePolicy
import com.example.market.domain.shared.Money
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.Currency

/**
 * application.yml 의 `market.fee.*` 값으로 FeePolicy 제공. 단순/안전.
 *
 * 실시간 정책 변경이 필요하면 admin DB 기반 provider 로 교체 가능.
 */
@Component
class YamlFeePolicyProvider(
    private val props: FeeProperties,
) : FeePolicyProvider {

    override fun current(): FeePolicy {
        val currency = Currency.getInstance(props.currency)
        return FeePolicy(
            BigDecimal.valueOf(props.sellerCommissionRate),
            BigDecimal.valueOf(props.buyerCommissionRate),
            Money.of(BigDecimal.valueOf(props.inspectionFee), currency),
            Money.of(BigDecimal.valueOf(props.shippingFee), currency),
            Money.of(BigDecimal.valueOf(props.fixedProcessingFee), currency),
        )
    }
}
