package com.example.market.domain.trading;

public enum ListingStatus {
    ACTIVE,      // 호가창에 노출 중
    MATCHED,     // 거래 체결됨
    CANCELLED,   // 판매자가 취소
    EXPIRED;     // 30일 경과 자동 만료

    public boolean isTerminal() { return this != ACTIVE; }
}
