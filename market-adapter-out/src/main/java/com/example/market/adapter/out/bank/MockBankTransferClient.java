package com.example.market.adapter.out.bank;

import com.example.market.application.port.out.BankTransferClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 정산 송금 mock — 항상 accept (또는 idempotencyKey 가 FAIL_ 로 시작 시 reject).
 */
@Component
@ConditionalOnProperty(name = "market.bank.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class MockBankTransferClient implements BankTransferClient {

    @Override
    public SendResult send(SendRequest req) {
        if (req.idempotencyKey().startsWith("FAIL_")) {
            return SendResult.rejected("simulated bank rejection");
        }
        String bankTxId = "mock-bank-" + UUID.randomUUID();
        log.info("[mock-bank] sent {} amount={} → {}", req.sellerId(), req.amount(), bankTxId);
        return SendResult.accepted(bankTxId);
    }
}
