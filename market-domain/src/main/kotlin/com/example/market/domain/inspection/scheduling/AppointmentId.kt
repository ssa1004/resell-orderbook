package com.example.market.domain.inspection.scheduling

import java.util.UUID

data class AppointmentId(@get:JvmName("value") val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        @JvmStatic
        fun newId(): AppointmentId = AppointmentId(UUID.randomUUID())

        @JvmStatic
        fun of(s: String): AppointmentId = AppointmentId(UUID.fromString(s))
    }
}
