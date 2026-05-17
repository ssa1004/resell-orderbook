package com.example.market.adapter.out.persistence.jpa.entity

import com.example.market.domain.inspection.InspectionOutcome
import com.example.market.domain.inspection.InspectionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "inspection_requests",
    indexes = [
        Index(name = "ix_inspection_trade", columnList = "trade_id", unique = true),
        Index(name = "ix_inspection_status", columnList = "status, requested_at"),
    ],
)
class InspectionRequestJpaEntity {

    @Id
    var id: UUID? = null

    @Column(name = "trade_id", nullable = false)
    var tradeId: UUID? = null

    @Column(name = "photo_urls", columnDefinition = "TEXT")
    var photoUrlsJson: String? = null // JSON array

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: InspectionStatus? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "result_outcome", length = 10)
    var resultOutcome: InspectionOutcome? = null

    @Column(name = "result_reason", length = 500)
    var resultReason: String? = null

    @Column(name = "result_note", length = 1000)
    var resultNote: String? = null

    @Column(name = "inspector_id", length = 64)
    var inspectorId: String? = null

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
