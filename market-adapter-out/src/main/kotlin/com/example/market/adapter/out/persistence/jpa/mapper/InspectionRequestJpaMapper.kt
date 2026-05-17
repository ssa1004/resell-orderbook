package com.example.market.adapter.out.persistence.jpa.mapper

import com.example.market.adapter.out.persistence.jpa.entity.InspectionRequestJpaEntity
import com.example.market.domain.inspection.InspectionRequest
import com.example.market.domain.inspection.InspectionRequestId
import com.example.market.domain.inspection.InspectionResult
import com.example.market.domain.shared.UserId
import com.example.market.domain.trading.TradeId
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

object InspectionRequestJpaMapper {

    private val MAPPER = ObjectMapper()
    private val STRING_LIST = object : TypeReference<List<String>>() {}

    @JvmStatic
    fun toEntity(r: InspectionRequest): InspectionRequestJpaEntity {
        val e = InspectionRequestJpaEntity()
        e.id = r.id.value
        e.tradeId = r.tradeId.value
        e.requestedAt = r.requestedAt
        e.status = r.status
        e.inspectorId = r.inspectorId?.value
        e.decidedAt = r.decidedAt
        e.photoUrlsJson = try {
            MAPPER.writeValueAsString(r.photoUrls())
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("photoUrls serialize failed", ex)
        }
        r.result?.let {
            e.resultOutcome = it.outcome
            e.resultReason = it.reason
            e.resultNote = it.note
        }
        e.version = r.version
        return e
    }

    @JvmStatic
    fun toDomain(e: InspectionRequestJpaEntity): InspectionRequest {
        val photos: List<String> = try {
            e.photoUrlsJson?.let { MAPPER.readValue(it, STRING_LIST) } ?: emptyList()
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException("photoUrls deserialize failed", ex)
        }
        val result = e.resultOutcome?.let {
            InspectionResult(it, e.resultReason, e.resultNote)
        }
        val inspector = e.inspectorId?.let { UserId.of(it) }
        return InspectionRequest.restore(
            InspectionRequestId(e.id!!),
            TradeId(e.tradeId!!),
            photos,
            e.requestedAt!!,
            e.status!!,
            result,
            inspector,
            e.decidedAt,
            e.version,
        )
    }
}
