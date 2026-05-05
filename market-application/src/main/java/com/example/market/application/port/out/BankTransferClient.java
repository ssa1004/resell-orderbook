package com.example.market.application.port.out;

import com.example.market.domain.shared.Money;
import com.example.market.domain.shared.UserId;

/**
 * 판매자 정산 송금. Mock 또는 실제 은행 API.
 */
public interface BankTransferClient {

    SendResult send(SendRequest request);

    record SendRequest(String idempotencyKey, UserId sellerId, Money amount, String memo) {}
    record SendResult(boolean accepted, String bankTransferId, String errorMessage) {
        public static SendResult accepted(String bankTransferId) {
            return new SendResult(true, bankTransferId, null);
        }
        public static SendResult rejected(String msg) {
            return new SendResult(false, null, msg);
        }
    }
}
