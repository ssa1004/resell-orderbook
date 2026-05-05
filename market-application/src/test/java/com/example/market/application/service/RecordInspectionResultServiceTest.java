package com.example.market.application.service;

import com.example.market.application.command.RecordInspectionResultCommand;
import com.example.market.application.port.out.EventPublisher;
import com.example.market.application.port.out.InspectionRequestRepository;
import com.example.market.application.port.out.TradeRepository;
import com.example.market.domain.catalog.SkuId;
import com.example.market.domain.inspection.InspectionOutcome;
import com.example.market.domain.inspection.InspectionRequest;
import com.example.market.domain.inspection.InspectionStatus;
import com.example.market.domain.settlement.FeePolicy;
import com.example.market.domain.shared.DomainEvent;
import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.Bid;
import com.example.market.domain.trading.Listing;
import com.example.market.domain.trading.Trade;
import com.example.market.domain.trading.TradeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordInspectionResultServiceTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final SkuId SKU = SkuId.newId();
    private static final UserId SELLER = UserId.of("seller");
    private static final UserId BUYER = UserId.of("buyer");
    private static final UserId INSPECTOR = UserId.of("inspector-1");
    private static final FeePolicy POLICY = new FeePolicy(
            new BigDecimal("3.0"), new BigDecimal("3.5"),
            money(3_000), money(3_000), money(1_000));

    private InspectionRequestRepository inspections;
    private TradeRepository trades;
    private EventPublisher events;
    private RecordInspectionResultService service;

    @BeforeEach
    void setUp() {
        inspections = mock(InspectionRequestRepository.class);
        trades = mock(TradeRepository.class);
        events = mock(EventPublisher.class);
        service = new RecordInspectionResultService(inspections, trades, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void pass_setsTradeToInspectionPassed() {
        Trade trade = tradeAtInspectionPending();
        InspectionRequest req = inspectionInProgress(trade);
        when(inspections.findById(req.id())).thenReturn(Optional.of(req));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        service.record(new RecordInspectionResultCommand(
                INSPECTOR, req.id(), InspectionOutcome.PASS, null, "authentic", List.of()));

        assertThat(req.status()).isEqualTo(InspectionStatus.DECIDED);
        assertThat(trade.status()).isEqualTo(TradeStatus.INSPECTION_PASSED);

        ArgumentCaptor<DomainEvent[]> captor = ArgumentCaptor.forClass(DomainEvent[].class);
        verify(events).publishAll(captor.capture());
        assertThat(captor.getValue())
                .anyMatch(e -> e instanceof InspectionRequest.InspectionDecided)
                .anyMatch(e -> e instanceof Trade.InspectionPassed);
    }

    @Test
    void fail_setsTradeToInspectionFailed_withReason() {
        Trade trade = tradeAtInspectionPending();
        InspectionRequest req = inspectionInProgress(trade);
        when(inspections.findById(req.id())).thenReturn(Optional.of(req));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        service.record(new RecordInspectionResultCommand(
                INSPECTOR, req.id(), InspectionOutcome.FAIL, "fake serial", "logo glue", List.of()));

        assertThat(trade.status()).isEqualTo(TradeStatus.INSPECTION_FAILED);
        assertThat(trade.inspectionFailReason()).isEqualTo("fake serial");
    }

    @Test
    void photoUrls_areAttached() {
        Trade trade = tradeAtInspectionPending();
        InspectionRequest req = inspectionInProgress(trade);
        when(inspections.findById(req.id())).thenReturn(Optional.of(req));
        when(trades.findById(trade.id())).thenReturn(Optional.of(trade));

        service.record(new RecordInspectionResultCommand(
                INSPECTOR, req.id(), InspectionOutcome.PASS, null, "ok",
                List.of("s3://photo-1", "s3://photo-2")));

        assertThat(req.photoUrls()).containsExactly("s3://photo-1", "s3://photo-2");
    }

    private Trade tradeAtInspectionPending() {
        Listing ask = Listing.place(SKU, SELLER, money(140_000), NOW);
        Bid bid = Bid.place(SKU, BUYER, money(150_000), NOW);
        Trade t = Trade.match(ask, bid, money(150_000), POLICY, NOW);
        t.authorizePayment("pg", NOW);
        t.startSellerShipping(NOW);
        t.arriveAtInspection(NOW);
        return t;
    }

    private InspectionRequest inspectionInProgress(Trade trade) {
        InspectionRequest r = InspectionRequest.open(trade.id(), NOW);
        r.assignInspector(INSPECTOR);
        return r;
    }

    private static Money money(long won) {
        return Money.of(BigDecimal.valueOf(won), KRW);
    }
}
