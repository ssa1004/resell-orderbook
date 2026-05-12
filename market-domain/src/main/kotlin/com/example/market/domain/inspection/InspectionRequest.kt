package com.example.market.domain.inspection

import com.example.market.domain.shared.DomainEvent
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import java.time.Instant
import java.util.Collections

/**
 * 검수 요청. Trade.SELLER_SHIPPING → 검수센터 도착 시 생성.
 *
 * <p>상태: PENDING (검수 대기) → IN_PROGRESS (담당자 배정) → DECIDED (결과 기록).
 * DECIDED 가 되면 [InspectionResult] 가 첨부됨.</p>
 *
 * <p>record-style accessor (id(), tradeId(), status() 등) 는 {@code @get:JvmName} 으로
 * Java/Kotlin 양쪽 호출자 호환 유지.</p>
 */
class InspectionRequest private constructor(
    @get:JvmName("id") val id: InspectionRequestId,
    @get:JvmName("tradeId") val tradeId: TradeId,
    private val _photoUrls: MutableList<String>,
    @get:JvmName("requestedAt") val requestedAt: Instant,
    status: InspectionStatus,
    result: InspectionResult?,
    inspectorId: UserId?,
    decidedAt: Instant?,
    @get:JvmName("version") val version: Long,
) {

    @get:JvmName("status")
    var status: InspectionStatus = status
        private set

    @get:JvmName("result")
    var result: InspectionResult? = result
        private set

    @get:JvmName("inspectorId")
    var inspectorId: UserId? = inspectorId
        private set

    @get:JvmName("decidedAt")
    var decidedAt: Instant? = decidedAt
        private set

    /** 외부 노출은 unmodifiable view — 내부 mutation 차단. */
    fun photoUrls(): List<String> = Collections.unmodifiableList(_photoUrls)

    fun assignInspector(inspectorId: UserId) {
        check(status == InspectionStatus.PENDING) { "must be PENDING to assign, was $status" }
        this.inspectorId = inspectorId
        this.status = InspectionStatus.IN_PROGRESS
    }

    fun addPhoto(photoUrl: String) {
        check(status != InspectionStatus.DECIDED) { "cannot add photo after DECIDED" }
        _photoUrls.add(photoUrl)
    }

    fun decide(result: InspectionResult, now: Instant): InspectionDecided {
        check(status == InspectionStatus.IN_PROGRESS) { "must be IN_PROGRESS to decide, was $status" }
        this.result = result
        this.decidedAt = now
        this.status = InspectionStatus.DECIDED
        return InspectionDecided(id, tradeId, result.outcome, result.reason, now)
    }

    companion object {
        @JvmStatic
        fun open(tradeId: TradeId, now: Instant): InspectionRequest =
            InspectionRequest(
                id = InspectionRequestId.newId(),
                tradeId = tradeId,
                _photoUrls = mutableListOf(),
                requestedAt = now,
                status = InspectionStatus.PENDING,
                result = null,
                inspectorId = null,
                decidedAt = null,
                version = 0L,
            )

        @JvmStatic
        fun restore(
            id: InspectionRequestId,
            tradeId: TradeId,
            photoUrls: List<String>,
            requestedAt: Instant,
            status: InspectionStatus,
            result: InspectionResult?,
            inspectorId: UserId?,
            decidedAt: Instant?,
            version: Long,
        ): InspectionRequest = InspectionRequest(
            id = id,
            tradeId = tradeId,
            _photoUrls = photoUrls.toMutableList(),
            requestedAt = requestedAt,
            status = status,
            result = result,
            inspectorId = inspectorId,
            decidedAt = decidedAt,
            version = version,
        )
    }

    /**
     * 검수 결정 도메인 이벤트 — InspectionRequest 가 DECIDED 로 전이될 때 발생.
     */
    data class InspectionDecided(
        @get:JvmName("requestId") val requestId: InspectionRequestId,
        @get:JvmName("tradeId") val tradeId: TradeId,
        @get:JvmName("outcome") val outcome: InspectionOutcome,
        @get:JvmName("reason") val reason: String?,
        private val occurredAtInstant: Instant,
    ) : DomainEvent {
        override fun aggregateId(): String = tradeId.toString()
        override fun occurredAt(): Instant = occurredAtInstant
    }
}
