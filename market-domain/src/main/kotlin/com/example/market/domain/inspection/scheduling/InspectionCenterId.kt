package com.example.market.domain.inspection.scheduling

import java.util.UUID

data class InspectionCenterId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): InspectionCenterId = InspectionCenterId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): InspectionCenterId = InspectionCenterId(UUID.fromString(s))
    }
}
