package com.example.market.adapter.web.exception

import com.example.market.adapter.web.dto.ErrorResponse
import com.example.market.application.exception.BidNotFoundException
import com.example.market.application.exception.InspectionRequestNotFoundException
import com.example.market.application.exception.ListingNotFoundException
import com.example.market.application.exception.PayoutNotFoundException
import com.example.market.application.exception.PgFailureException
import com.example.market.application.exception.ProductNotFoundException
import com.example.market.application.exception.RefundNotFoundException
import com.example.market.application.exception.SkuNotFoundException
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.exception.UnauthorizedTradeOperationException
import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import com.example.market.domain.trading.BidOwnershipViolation
import com.example.market.domain.trading.ListingOwnershipViolation
import io.micrometer.tracing.Tracer
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 도메인/application 예외 → HTTP status 매핑.
 */
@RestControllerAdvice
class GlobalExceptionHandler(private val tracer: Tracer) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DuplicateRequestException::class)
    fun handleDuplicate(e: DuplicateRequestException) =
        body(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", e.message ?: "duplicate request")

    @ExceptionHandler(
        ProductNotFoundException::class,
        SkuNotFoundException::class,
        ListingNotFoundException::class,
        BidNotFoundException::class,
        TradeNotFoundException::class,
        InspectionRequestNotFoundException::class,
        PayoutNotFoundException::class,
        RefundNotFoundException::class,
    )
    fun handleNotFound(e: RuntimeException) =
        body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "not found")

    @ExceptionHandler(
        ListingOwnershipViolation::class,
        BidOwnershipViolation::class,
        UnauthorizedTradeOperationException::class,
    )
    fun handleForbidden(e: RuntimeException) =
        body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.message ?: "forbidden")

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException) =
        body(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.message ?: "illegal state")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException) =
        body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "bad request")

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException) =
        body(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "header missing: ${e.headerName}")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val msg = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(e: ConstraintViolationException) =
        body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.message ?: "validation failed")

    @ExceptionHandler(PgFailureException::class)
    fun handlePgFailure(e: PgFailureException): ResponseEntity<ErrorResponse> {
        log.warn("PG failure: code={} msg={}", e.errorCode(), e.message)
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.errorCode(), e.message ?: "PG failure")
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("unhandled exception", e)
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "internal server error")
    }

    private fun body(status: HttpStatus, code: String, message: String): ResponseEntity<ErrorResponse> {
        val traceId = tracer.currentSpan()?.context()?.traceId()
        return ResponseEntity.status(status).body(ErrorResponse(code, message, traceId))
    }
}
