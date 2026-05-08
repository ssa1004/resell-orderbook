package com.example.market.adapter.web.exception

import com.example.market.application.exception.InspectionExceptions
import com.example.market.application.exception.ListingNotFoundException
import com.example.market.domain.inspection.scheduling.AppointmentId
import com.example.market.domain.inspection.scheduling.InspectionCenterId
import com.example.market.domain.trading.ListingId
import com.example.market.domain.trading.TradeId
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.HttpInputMessage
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * GlobalExceptionHandler 단위 테스트 — handler 메서드를 직접 호출해 매핑/메시지 검증.
 *
 * <p>전체 라우팅 (MockMvc) 통합은 e2e 에서 다룸. 여기서는 매핑 표만 빠르게 락.
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler(Tracer.NOOP)

    @Test
    fun `not found exception maps to 404`() {
        val r = handler.handleNotFound(ListingNotFoundException(ListingId.newId()))
        assertThat(r.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(r.body?.code).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `inspection center not found maps to 404`() {
        val r = handler.handleNotFound(
            InspectionExceptions.CenterNotFoundException(InspectionCenterId.newId()))
        assertThat(r.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `inspection appointment not found maps to 404`() {
        val r = handler.handleNotFound(
            InspectionExceptions.AppointmentNotFoundException(AppointmentId.newId()))
        assertThat(r.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `slot full maps to 409`() {
        val r = handler.handleSlotConflict(
            InspectionExceptions.SlotFullException(
                InspectionCenterId.newId(), Instant.parse("2026-05-04T14:00:00Z"), 3L))
        assertThat(r.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(r.body?.code).isEqualTo("SLOT_CONFLICT")
    }

    @Test
    fun `already booked trade maps to 409`() {
        val r = handler.handleSlotConflict(
            InspectionExceptions.AlreadyBookedException(TradeId.newId(), AppointmentId.newId()))
        assertThat(r.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(r.body?.code).isEqualTo("SLOT_CONFLICT")
    }

    @Test
    fun `too late to book maps to 422`() {
        val r = handler.handleTooLateToBook(
            InspectionExceptions.TooLateToBookException(
                InspectionCenterId.newId(), Instant.parse("2026-05-04T14:00:00Z")))
        assertThat(r.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(r.body?.code).isEqualTo("TOO_LATE_TO_BOOK")
    }

    @Test
    fun `bad timestamp maps to 400 with neutral message`() {
        val r = handler.handleDateTimeParse(
            DateTimeParseException("foo", "not-a-date", 0))
        assertThat(r.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        // 내부 파서 메시지가 그대로 노출되지 않는지
        assertThat(r.body?.message).isEqualTo("invalid timestamp format")
    }

    @Test
    fun `missing required query param maps to 400`() {
        val r = handler.handleMissingParam(
            MissingServletRequestParameterException("from", "String"))
        assertThat(r.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(r.body?.code).isEqualTo("MISSING_PARAM")
        assertThat(r.body?.message).contains("from")
    }

    @Test
    fun `type mismatch maps to 400 without leaking internals`() {
        val mockParam = org.springframework.core.MethodParameter.forExecutable(
            String::class.java.getMethod("toString"), -1)
        val r = handler.handleTypeMismatch(
            MethodArgumentTypeMismatchException("abc", Int::class.java, "depth", mockParam, RuntimeException()))
        assertThat(r.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(r.body?.message).isEqualTo("invalid value for 'depth'")
    }

    @Test
    fun `malformed body maps to 400 with safe message`() {
        val empty = object : HttpInputMessage {
            override fun getBody(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getHeaders() = org.springframework.http.HttpHeaders()
        }
        val r = handler.handleMalformedBody(
            HttpMessageNotReadableException("internal parser detail", empty))
        assertThat(r.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(r.body?.message).isEqualTo("request body is malformed")
    }
}
