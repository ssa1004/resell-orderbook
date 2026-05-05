package com.example.market.adapter.out.persistence.jpa.entity;

import com.example.market.domain.inspection.InspectionOutcome;
import com.example.market.domain.inspection.InspectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inspection_requests", indexes = {
        @Index(name = "ix_inspection_trade", columnList = "trade_id", unique = true),
        @Index(name = "ix_inspection_status", columnList = "status, requested_at")
})
@Getter
@Setter
@NoArgsConstructor
public class InspectionRequestJpaEntity {

    @Id
    private UUID id;

    @Column(name = "trade_id", nullable = false)
    private UUID tradeId;

    @Column(name = "photo_urls", columnDefinition = "TEXT")
    private String photoUrlsJson;       // JSON array

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InspectionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_outcome", length = 10)
    private InspectionOutcome resultOutcome;

    @Column(name = "result_reason", length = 500)
    private String resultReason;

    @Column(name = "result_note", length = 1000)
    private String resultNote;

    @Column(name = "inspector_id", length = 64)
    private String inspectorId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
