package com.example.market.e2e

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * e2e IT 전용 — 매 테스트 전에 도메인 테이블 모두 비움.
 *
 * `@SpringBootTest` 가 컨텍스트를 캐시하다 보니 같은 IT 클래스 안의 테스트들이
 * Postgres 인스턴스를 공유 (Testcontainers `@Container` static). 한 테스트가 만든
 * Trade / outbox 이벤트가 다음 테스트의 `outbox.findAll()` / `findByStatus` 결과에
 * 섞여 들어와 단언이 꼬이는 일을 막는다.
 *
 * 주의: `skus / products` 도 비우므로 자식 클래스의 `@BeforeEach` 가 부모의 `@BeforeEach`
 * 뒤에 실행되어 새 Sku 를 만들도록 JUnit 5 의 호출 순서에 의존한다 (부모 → 자식).
 * `shedlock / flyway_schema_history` 는 운영 메타라 건드리지 않는다.
 */
abstract class E2ECleanupSupport {

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun truncateAllDomainTables() {
        // FK 순환은 CASCADE 로 풀어버린다. RESTART IDENTITY 로 PK 시퀀스도 초기화.
        jdbc.execute(
            """
            TRUNCATE TABLE
              outbox,
              refunds,
              payouts,
              inspection_requests,
              trades,
              listings,
              bids,
              skus,
              products
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }
}
