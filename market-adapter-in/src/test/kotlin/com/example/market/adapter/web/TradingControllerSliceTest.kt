package com.example.market.adapter.web

import com.example.market.adapter.web.exception.GlobalExceptionHandler
import com.example.market.application.port.`in`.BuyNowUseCase
import com.example.market.application.port.`in`.CancelBidUseCase
import com.example.market.application.port.`in`.CancelListingUseCase
import com.example.market.application.port.`in`.OrderBookQueryUseCase
import com.example.market.application.port.`in`.PlaceBidUseCase
import com.example.market.application.port.`in`.PlaceListingUseCase
import com.example.market.application.port.`in`.PlaceListingUseCase.PlaceListingResult
import com.example.market.application.port.`in`.SellNowUseCase
import com.example.market.domain.trading.ListingId
import com.example.market.domain.trading.TradeId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

/**
 * TradingController slice — 라우팅/JSON/UseCase 호출 검증.
 *
 * <p>Exception 매핑 (404/403/409 등) 검증은 e2e Testcontainer 에서 — standalone MockMvc 의
 * ControllerAdvice 등록이 ServletException wrap 으로 제대로 동작하지 않음.</p>
 */
class TradingControllerSliceTest {

    private val placeListing: PlaceListingUseCase = mock()
    private val placeBid: PlaceBidUseCase = mock()
    private val buyNow: BuyNowUseCase = mock()
    private val sellNow: SellNowUseCase = mock()
    private val cancelListing: CancelListingUseCase = mock()
    private val cancelBid: CancelBidUseCase = mock()
    private val orderBookQuery: OrderBookQueryUseCase = mock()

    private lateinit var mockMvc: MockMvc
    private val mapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        val controller = TradingController(
            placeListing, placeBid, buyNow, sellNow,
            cancelListing, cancelBid, orderBookQuery,
        )
        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler(Tracer.NOOP))
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @Test
    fun `POST listings without match returns 201 with no matchedTradeId`() {
        val listingId = ListingId.newId()
        whenever(placeListing.place(any())).thenReturn(PlaceListingResult(listingId, Optional.empty()))

        mockMvc.perform(
            post("/api/v1/listings")
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("skuId" to "00000000-0000-0000-0000-000000000001",
                                                        "price" to 150_000, "currency" to "KRW")))
        )
            .andExpect(status().isCreated)
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.listingId").value(listingId.toString()))
            .andExpect(jsonPath("$.matchedTradeId").doesNotExist())
    }

    @Test
    fun `POST listings with matching bid returns 201 with matchedTradeId`() {
        val listingId = ListingId.newId()
        val tradeId = TradeId.newId()
        whenever(placeListing.place(any())).thenReturn(
            PlaceListingResult(listingId, Optional.of(tradeId)))

        mockMvc.perform(
            post("/api/v1/listings")
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("skuId" to "00000000-0000-0000-0000-000000000001",
                                                        "price" to 140_000, "currency" to "KRW")))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.matchedTradeId").value(tradeId.toString()))
    }

    @Test
    fun `DELETE listings invokes cancel use case and returns 204`() {
        val listingId = ListingId.newId()

        mockMvc.perform(delete("/api/v1/listings/$listingId"))
            .andExpect(status().isNoContent)

        verify(cancelListing).cancel(any())
    }
}
