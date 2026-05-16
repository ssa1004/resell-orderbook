package com.example.market.adapter.web.exception

import com.example.market.adapter.web.dto.ErrorResponse
import com.example.market.application.exception.BidNotFoundException
import com.example.market.application.exception.InspectionExceptions
import com.example.market.application.exception.InspectionRequestNotFoundException
import com.example.market.application.exception.ListingNotFoundException
import com.example.market.application.exception.PayoutNotFoundException
import com.example.market.application.exception.PgFailureException
import com.example.market.application.exception.ProductNotFoundException
import com.example.market.application.exception.RefundNotFoundException
import com.example.market.application.exception.SkuNotFoundException
import com.example.market.application.exception.TradeNotFoundException
import com.example.market.application.exception.UnauthorizedTradeOperationException
import com.example.market.application.pagination.CursorCodec
import com.example.market.application.port.out.IdempotencyKeyStore.DuplicateRequestException
import com.example.market.domain.trading.BidOwnershipViolation
import com.example.market.domain.trading.ListingOwnershipViolation
import io.micrometer.tracing.Tracer
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.format.DateTimeParseException

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
        InspectionExceptions.CenterNotFoundException::class,
        InspectionExceptions.AppointmentNotFoundException::class,
        PayoutNotFoundException::class,
        RefundNotFoundException::class,
    )
    fun handleNotFound(e: RuntimeException) =
        body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "not found")

    /**
     * 검수 슬롯 capacity 초과 / 중복 예약 — 둘 다 다른 사용자(또는 같은 사용자의 중복 클릭)와의
     * 충돌이라 409 가 적절. ADR-0017 의 over-booking 방지 흐름 참조.
     */
    @ExceptionHandler(
        InspectionExceptions.SlotFullException::class,
        InspectionExceptions.AlreadyBookedException::class,
    )
    fun handleSlotConflict(e: RuntimeException) =
        body(HttpStatus.CONFLICT, "SLOT_CONFLICT", e.message ?: "slot conflict")

    /** 예약 마감 시간 안에 들어와 거절 — 클라이언트가 다른 슬롯을 골라야 하므로 422. */
    @ExceptionHandler(InspectionExceptions.TooLateToBookException::class)
    fun handleTooLateToBook(e: InspectionExceptions.TooLateToBookException) =
        body(HttpStatus.UNPROCESSABLE_ENTITY, "TOO_LATE_TO_BOOK", e.message ?: "too late to book")

    @ExceptionHandler(
        ListingOwnershipViolation::class,
        BidOwnershipViolation::class,
        UnauthorizedTradeOperationException::class,
        InspectionExceptions.UnauthorizedAppointmentOperationException::class,
    )
    fun handleForbidden(e: RuntimeException) =
        body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.message ?: "forbidden")

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException) =
        body(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.message ?: "illegal state")

    /** 클라이언트가 깨진 / 위조된 cursor 를 보낸 경우 — 400 + 명확한 error code. */
    @ExceptionHandler(CursorCodec.InvalidCursorException::class)
    fun handleInvalidCursor(e: CursorCodec.InvalidCursorException) =
        body(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "invalid cursor")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException) =
        body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "bad request")

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException) =
        body(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "header missing: ${e.headerName}")

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException) =
        body(HttpStatus.BAD_REQUEST, "MISSING_PARAM", "parameter missing: ${e.parameterName}")

    /** {@code @PathVariable Int}, enum 변환 실패 등 — 잘못된 타입은 400 으로. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException) =
        body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "invalid value for '${e.name}'")

    /** ISO-8601 timestamp 파싱 실패 — 클라이언트 입력 오류라 400. */
    @ExceptionHandler(DateTimeParseException::class)
    fun handleDateTimeParse(e: DateTimeParseException) =
        body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "invalid timestamp format")

    /** 깨진 JSON / 알 수 없는 필드 등 — 본문을 못 읽으면 400. 내부 메시지는 노출하지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(e: HttpMessageNotReadableException) =
        body(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "request body is malformed")

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
        log.warn("PG failure: code={} msg={}", e.errorCode, e.message)
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.errorCode, e.message ?: "PG failure")
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
