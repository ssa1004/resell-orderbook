package com.example.market.domain.settlement;

public enum PayoutStatus {
    SCHEDULED,   // 정산 예정
    SENT,        // 송금 요청됨
    COMPLETED,   // 정산 완료
    FAILED;      // 송금 실패
    public boolean isTerminal() { return this == COMPLETED || this == FAILED; }
}
