package com.example.market;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith 모듈 경계 검증 + PUML 다이어그램 자동 생성.
 */
class MarketApplicationModulithTest {

    private final ApplicationModules modules = ApplicationModules.of(MarketApplication.class);

    @Test
    void verifiesModulesAreCompliant() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules)
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();
    }
}
