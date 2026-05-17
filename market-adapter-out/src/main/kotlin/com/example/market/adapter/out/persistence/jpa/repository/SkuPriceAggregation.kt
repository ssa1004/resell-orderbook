package com.example.market.adapter.out.persistence.jpa.repository

import java.math.BigDecimal

/**
 * 24h 가격 집계 응답 DTO — [SpringDataPriceTickRepository.aggregate] 의 result projection.
 *
 * Hibernate JPQL 의 `SELECT new ...` (constructor expression) 는 fully-qualified
 * 클래스 이름을 reflection 으로 로드한다. nested type 은 binary name 이 `Outer$Inner`
 * 이지만 JPQL 안에서는 `.` 표기만 허용되어 ClassNotFound 가 난다 — 그래서 top-level
 * 로 분리.
 *
 * @param count 구간 내 PriceTick 수
 * @param min   최저 체결가 (count=0 이면 null)
 * @param avg   평균 체결가 (count=0 이면 null)
 * @param max   최고 체결가 (count=0 이면 null)
 */
@JvmRecord
data class SkuPriceAggregation(
    val count: Long,
    val min: BigDecimal?,
    val avg: Double?,
    val max: BigDecimal?,
)
