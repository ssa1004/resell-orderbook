package com.example.market.domain.inspection;

import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 검수 요청. Trade.SELLER_SHIPPING → 검수센터 도착 시 생성.
 *
 * <p>상태: PENDING (검수 대기) → IN_PROGRESS (담당자 배정) → DECIDED (결과 기록).
 * DECIDED 가 되면 {@link InspectionResult} 가 첨부됨.</p>
 */
public class InspectionRequest {

    private final InspectionRequestId id;
    private final TradeId tradeId;
    private final List<String> photoUrls;       // S3 presigned URL
    private final Instant requestedAt;
    private InspectionStatus status;
    private InspectionResult result;            // null until DECIDED
    private UserId inspectorId;
    private Instant decidedAt;
    private long version;

    private InspectionRequest(InspectionRequestId id, TradeId tradeId, List<String> photoUrls,
                              Instant requestedAt, InspectionStatus status, InspectionResult result,
                              UserId inspectorId, Instant decidedAt, long version) {
        this.id = id;
        this.tradeId = tradeId;
        this.photoUrls = new ArrayList<>(photoUrls);
        this.requestedAt = requestedAt;
        this.status = status;
        this.result = result;
        this.inspectorId = inspectorId;
        this.decidedAt = decidedAt;
        this.version = version;
    }

    public static InspectionRequest open(TradeId tradeId, Instant now) {
        Objects.requireNonNull(tradeId);
        return new InspectionRequest(InspectionRequestId.newId(), tradeId, List.of(),
                now, InspectionStatus.PENDING, null, null, null, 0L);
    }

    public static InspectionRequest restore(InspectionRequestId id, TradeId tradeId, List<String> photoUrls,
                                            Instant requestedAt, InspectionStatus status, InspectionResult result,
                                            UserId inspectorId, Instant decidedAt, long version) {
        return new InspectionRequest(id, tradeId, photoUrls, requestedAt, status, result,
                inspectorId, decidedAt, version);
    }

    public void assignInspector(UserId inspectorId) {
        if (status != InspectionStatus.PENDING) {
            throw new IllegalStateException("must be PENDING to assign, was " + status);
        }
        Objects.requireNonNull(inspectorId);
        this.inspectorId = inspectorId;
        this.status = InspectionStatus.IN_PROGRESS;
    }

    public void addPhoto(String photoUrl) {
        if (status == InspectionStatus.DECIDED) {
            throw new IllegalStateException("cannot add photo after DECIDED");
        }
        Objects.requireNonNull(photoUrl);
        photoUrls.add(photoUrl);
    }

    public InspectionDecided decide(InspectionResult result, Instant now) {
        if (status != InspectionStatus.IN_PROGRESS) {
            throw new IllegalStateException("must be IN_PROGRESS to decide, was " + status);
        }
        Objects.requireNonNull(result);
        this.result = result;
        this.decidedAt = now;
        this.status = InspectionStatus.DECIDED;
        return new InspectionDecided(id, tradeId, result.outcome(), result.reason(), now);
    }

    public InspectionRequestId id() { return id; }
    public TradeId tradeId() { return tradeId; }
    public List<String> photoUrls() { return Collections.unmodifiableList(photoUrls); }
    public Instant requestedAt() { return requestedAt; }
    public InspectionStatus status() { return status; }
    public InspectionResult result() { return result; }
    public UserId inspectorId() { return inspectorId; }
    public Instant decidedAt() { return decidedAt; }
    public long version() { return version; }

    public record InspectionDecided(InspectionRequestId requestId, TradeId tradeId,
                                    InspectionOutcome outcome, String reason, Instant occurredAt)
            implements DomainEvent {
        @Override public String aggregateId() { return tradeId.toString(); }
    }
}
