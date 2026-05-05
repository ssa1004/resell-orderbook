package com.example.market.adapter.out.persistence.jpa.mapper;

import com.example.market.adapter.out.persistence.jpa.entity.InspectionRequestJpaEntity;
import com.example.market.domain.inspection.InspectionRequest;
import com.example.market.domain.inspection.InspectionRequestId;
import com.example.market.domain.inspection.InspectionResult;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class InspectionRequestJpaMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private InspectionRequestJpaMapper() {}

    public static InspectionRequestJpaEntity toEntity(InspectionRequest r) {
        InspectionRequestJpaEntity e = new InspectionRequestJpaEntity();
        e.setId(r.id().value());
        e.setTradeId(r.tradeId().value());
        e.setRequestedAt(r.requestedAt());
        e.setStatus(r.status());
        e.setInspectorId(r.inspectorId() != null ? r.inspectorId().value() : null);
        e.setDecidedAt(r.decidedAt());
        try {
            e.setPhotoUrlsJson(MAPPER.writeValueAsString(r.photoUrls()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("photoUrls serialize failed", ex);
        }
        if (r.result() != null) {
            e.setResultOutcome(r.result().outcome());
            e.setResultReason(r.result().reason());
            e.setResultNote(r.result().note());
        }
        e.setVersion(r.version());
        return e;
    }

    public static InspectionRequest toDomain(InspectionRequestJpaEntity e) {
        List<String> photos;
        try {
            photos = e.getPhotoUrlsJson() != null
                    ? MAPPER.readValue(e.getPhotoUrlsJson(), STRING_LIST)
                    : List.of();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("photoUrls deserialize failed", ex);
        }
        InspectionResult result = null;
        if (e.getResultOutcome() != null) {
            result = new InspectionResult(e.getResultOutcome(), e.getResultReason(), e.getResultNote());
        }
        UserId inspector = e.getInspectorId() != null ? UserId.of(e.getInspectorId()) : null;
        return InspectionRequest.restore(
                new InspectionRequestId(e.getId()),
                new TradeId(e.getTradeId()),
                photos, e.getRequestedAt(),
                e.getStatus(), result, inspector, e.getDecidedAt(), e.getVersion());
    }
}
