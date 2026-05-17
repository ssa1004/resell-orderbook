package com.example.market

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulith
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Resell Market Platform — 한정판 스니커즈 리셀 마켓 백엔드.
 *
 * Spring Modulith 가 모듈 경계를 검증 — [com.example.market.MarketApplicationModulithTest].
 */
@SpringBootApplication(scanBasePackages = ["com.example.market"])
@ConfigurationPropertiesScan(basePackages = ["com.example.market"])
@Modulith(systemName = "resell-orderbook")
@EnableScheduling
open class MarketApplication

fun main(args: Array<String>) {
    runApplication<MarketApplication>(*args)
}
