package com.example.market.application.port.in;

public interface ExpireStaleBidsUseCase {
    int expireBatch(int batchSize);
}
