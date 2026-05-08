package com.example.market.adapter.out.bank;

import com.example.market.application.port.out.BankTransferClient;
import com.example.market.application.service.SensitiveLogging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 정산 송금 mock — 항상 accept (또는 idempotencyKey 가 FAIL_ 로 시작 시 reject).
 *
 * <p>같은 코드 경로가 운영의 실제 은행 어댑터로 진화한다. 송금 금액과 수신자 식별자는
 * 그대로 INFO 로그에 박지 않는다 — 운영 추적용 단서만 남기고 정확한 값은
 * {@link SensitiveLogging#mask}/{@link SensitiveLogging#maskAmount} 로 가린다.</p>
 */
@Component("rawBankTransferClient")
@ConditionalOnProperty(name = "market.bank.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class MockBankTransferClient implements BankTransferClient {

    @Override
    public SendResult send(SendRequest req) {
        if (req.idempotencyKey().startsWith("FAIL_")) {
            return SendResult.rejected("simulated bank rejection");
        }
        String bankTxId = "mock-bank-" + UUID.randomUUID();
        log.info("[mock-bank] sent seller={} amount={} → {}",
                SensitiveLogging.mask(req.sellerId().value()),
                SensitiveLogging.maskAmount(req.amount().amount()),
                bankTxId);
        return SendResult.accepted(bankTxId);
    }
}
