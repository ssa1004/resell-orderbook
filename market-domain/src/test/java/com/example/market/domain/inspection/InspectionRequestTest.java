package com.example.market.domain.inspection;

import com.example.market.domain.shared.UserId;
import com.example.market.domain.trading.TradeId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionRequestTest {

    private static final Instant NOW = Instant.parse("2026-05-04T00:00:00Z");
    private static final UserId INSPECTOR = UserId.of("inspector-1");

    @Test
    void open_startsPending() {
        InspectionRequest r = InspectionRequest.open(TradeId.newId(), NOW);
        assertThat(r.status()).isEqualTo(InspectionStatus.PENDING);
        assertThat(r.photoUrls()).isEmpty();
    }

    @Test
    void assignInspector_movesToInProgress() {
        InspectionRequest r = InspectionRequest.open(TradeId.newId(), NOW);
        r.assignInspector(INSPECTOR);
        assertThat(r.status()).isEqualTo(InspectionStatus.IN_PROGRESS);
        assertThat(r.inspectorId()).isEqualTo(INSPECTOR);
    }

    @Test
    void cannotAssignTwice() {
        InspectionRequest r = InspectionRequest.open(TradeId.newId(), NOW);
        r.assignInspector(INSPECTOR);
        assertThatThrownBy(() -> r.assignInspector(UserId.of("inspector-2")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decide_pass_emitsEventWithPassOutcome() {
        InspectionRequest r = InspectionRequest.open(TradeId.newId(), NOW);
        r.assignInspector(INSPECTOR);
        var ev = r.decide(InspectionResult.pass("authentic"), NOW);
        assertThat(r.status()).isEqualTo(InspectionStatus.DECIDED);
        assertThat(ev.outcome()).isEqualTo(InspectionOutcome.PASS);
    }

    @Test
    void decide_fail_emitsEventWithFailOutcome() {
        InspectionRequest r = InspectionRequest.open(TradeId.newId(), NOW);
        r.assignInspector(INSPECTOR);
        var ev = r.decide(InspectionResult.fail("fake serial", "logo glue"), NOW);
        assertThat(r.status()).isEqualTo(InspectionStatus.DECIDED);
        assertThat(ev.outcome()).isEqualTo(InspectionOutcome.FAIL);
        assertThat(ev.reason()).isEqualTo("fake serial");
    }

    @Test
    void resultRequiresReasonForFail() {
        assertThatThrownBy(() -> InspectionResult.fail(null, "note"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InspectionResult.fail(" ", "note"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotAddPhotoAfterDecided() {
        InspectionRequest r = InspectionRequest.open(TradeId.newId(), NOW);
        r.assignInspector(INSPECTOR);
        r.decide(InspectionResult.pass(null), NOW);
        assertThatThrownBy(() -> r.addPhoto("s3://photo")).isInstanceOf(IllegalStateException.class);
    }
}
