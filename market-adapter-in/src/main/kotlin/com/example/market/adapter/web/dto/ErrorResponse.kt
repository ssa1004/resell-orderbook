package com.example.market.adapter.web.dto

import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val traceId: String? = null,
    val timestamp: Instant = Instant.now(),
)
