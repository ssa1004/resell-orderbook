package com.example.market.adapter.web

import com.example.market.adapter.web.auth.CallerExtractor
import com.example.market.adapter.web.exception.GlobalExceptionHandler
import com.example.market.application.dlq.DlqAction
import com.example.market.application.dlq.DlqActionResult
import com.example.market.application.dlq.DlqAdminRateLimitedException
import com.example.market.application.dlq.DlqBulkDryRunResult
import com.example.market.application.dlq.DlqBulkJob
import com.example.market.application.dlq.DlqBulkStatus
import com.example.market.application.dlq.DlqMessage
import com.example.market.application.dlq.DlqMessageDetail
import com.example.market.application.dlq.DlqMessageNotFoundException
import com.example.market.application.dlq.DlqQuery
import com.example.market.application.dlq.DlqSource
import com.example.market.application.dlq.DlqStats
import com.example.market.application.pagination.CursorPage
import com.example.market.application.port.`in`.DlqAdminUseCase
import com.example.market.application.port.`in`.DlqBulkAdminUseCase
import com.example.market.application.port.`in`.DlqBulkSubmission
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.tracing.Tracer
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * AdminDlqController slice — 라우팅 / 직렬화 / use-case 호출 흐름 + 핵심 응답 형식 검증.
 *
 * Spring Security 의 PreAuthorize 는 standalone MockMvc 에선 동작하지 않으므로 보안 분기는
 * 별 IT 에서 검증. 여기서는 컨트롤러의 mapping 과 DTO 변환만 확인.
 */
class AdminDlqControllerSliceTest {

    private val dlqAdmin: DlqAdminUseCase = mock()
    private val dlqBulk: DlqBulkAdminUseCase = mock()
    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        val controller = AdminDlqController(dlqAdmin, dlqBulk, CallerExtractor(jwtEnabled = false))
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler(Tracer.NOOP))
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @Test
    fun `GET dlq returns paginated items with source and sku`() {
        val now = Instant.parse("2026-05-15T10:00:00Z")
        whenever(dlqAdmin.list(any())).thenReturn(
            CursorPage.last(
                listOf(
                    DlqMessage("msg-1", DlqSource.REFUND, "market.inspectionfailed",
                        "PgFailureException", "PG down", now, 2, "trade-1", "sku-A"),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/admin/dlq?source=REFUND&skuId=sku-A&size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].messageId").value("msg-1"))
            .andExpect(jsonPath("$.items[0].source").value("REFUND"))
            .andExpect(jsonPath("$.items[0].skuId").value("sku-A"))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
    }

    @Test
    fun `GET dlq missing message returns 404`() {
        whenever(dlqAdmin.detail(eq("missing"))).thenThrow(DlqMessageNotFoundException("missing"))

        mockMvc.perform(get("/api/v1/admin/dlq/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `POST replay returns 202 with action body and audit-friendly fields`() {
        val now = Instant.parse("2026-05-15T10:05:00Z")
        whenever(dlqAdmin.detail(any())).thenReturn(messageDetail("msg-1", now))
        whenever(dlqAdmin.perform(eq("msg-1"), eq(DlqAction.REPLAY), any<String>(), anyOrNull<String>())).thenReturn(
            DlqActionResult("msg-1", DlqAction.REPLAY, now, "anonymous", null,
                "trade-1", "sku-A"),
        )

        mockMvc.perform(post("/api/v1/admin/dlq/msg-1/replay")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.action").value("REPLAY"))
            .andExpect(jsonPath("$.tradeId").value("trade-1"))
            .andExpect(jsonPath("$.skuId").value("sku-A"))

        verify(dlqAdmin).perform(eq("msg-1"), eq(DlqAction.REPLAY), any<String>(), anyOrNull<String>())
    }

    @Test
    fun `POST discard requires reason — IllegalArgumentException maps to 400`() {
        whenever(dlqAdmin.perform(any<String>(), eq(DlqAction.DISCARD), any<String>(), anyOrNull<String>()))
            .thenThrow(IllegalArgumentException("discard requires reason"))

        mockMvc.perform(post("/api/v1/admin/dlq/msg-1/discard")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
    }

    @Test
    fun `POST bulk-replay without confirm returns dry-run result`() {
        whenever(dlqBulk.bulkReplay(any())).thenReturn(
            DlqBulkSubmission.DryRun(
                DlqBulkDryRunResult(DlqAction.REPLAY, DlqSource.REFUND, 42L, emptyList()),
            ),
        )

        mockMvc.perform(post("/api/v1/admin/dlq/bulk-replay")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"source":"REFUND"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mode").value("DRY_RUN"))
            .andExpect(jsonPath("$.matched").value(42))
    }

    @Test
    fun `POST bulk-discard with confirm returns 202 + job body`() {
        val now = Instant.parse("2026-05-15T10:10:00Z")
        whenever(dlqBulk.bulkDiscard(any())).thenReturn(
            DlqBulkSubmission.Queued(
                DlqBulkJob("job-1", DlqAction.DISCARD, DlqSource.OUTBOX, "anonymous",
                    now, null, null, DlqBulkStatus.QUEUED, 7, 0, 0, 0, null),
            ),
        )

        mockMvc.perform(post("/api/v1/admin/dlq/bulk-discard")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"source":"OUTBOX","confirm":true,"reason":"stale"}"""))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.mode").value("QUEUED"))
            .andExpect(jsonPath("$.id").value("job-1"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.totalCount").value(7))
    }

    @Test
    fun `GET stats returns aggregated body including bySku`() {
        val from = Instant.parse("2026-05-15T09:00:00Z")
        val to = Instant.parse("2026-05-15T10:00:00Z")
        whenever(dlqAdmin.stats(any())).thenReturn(
            DlqStats(from, to, java.time.Duration.ofMinutes(15), 5,
                emptyList(),
                mapOf(DlqSource.REFUND to 5L),
                listOf(com.example.market.application.dlq.DlqErrorTypeCount("PgFailureException", 5L)),
                listOf(com.example.market.application.dlq.DlqSkuCount("sku-popular", 3L)),
            ),
        )

        mockMvc.perform(get("/api/v1/admin/dlq/stats?from=2026-05-15T09:00:00Z&to=2026-05-15T10:00:00Z"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.bySku[0].name").value("sku-popular"))
            .andExpect(jsonPath("$.bySku[0].count").value(3))
            .andExpect(jsonPath("$.bySource.REFUND").value(5))
    }

    @Test
    fun `rate-limited admin call returns 429 + Retry-After`() {
        whenever(dlqAdmin.list(any())).thenThrow(DlqAdminRateLimitedException(13L))

        mockMvc.perform(get("/api/v1/admin/dlq"))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "13"))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
    }

    private fun messageDetail(id: String, at: Instant): DlqMessageDetail {
        val summary = DlqMessage(id, DlqSource.REFUND, "market.inspectionfailed",
            "PgFailureException", "PG down", at, 1, "trade-1", "sku-A")
        return DlqMessageDetail(summary, "{}", "stack", emptyMap(), 0, 0L, at, at)
    }
}
