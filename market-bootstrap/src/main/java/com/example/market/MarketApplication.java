package com.example.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Resell Market Platform — KREAM/StockX 류 한정판 리셀 마켓 백엔드.
 *
 * <p>Spring Modulith 가 모듈 경계를 검증 — {@link com.example.market.MarketApplicationModulithTest}.</p>
 */
@SpringBootApplication(scanBasePackages = "com.example.market")
@ConfigurationPropertiesScan(basePackages = "com.example.market")
@Modulith(systemName = "resell-orderbook")
@EnableScheduling
public class MarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketApplication.class, args);
    }
}
