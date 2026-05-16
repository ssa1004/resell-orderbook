package com.example.market.application.port.out

import com.example.market.domain.settlement.FeePolicy

/**
 * 현재 시점의 수수료 정책 — application.yml 또는 admin DB. 매칭 시 snapshot.
 */
interface FeePolicyProvider {
    fun current(): FeePolicy
}
