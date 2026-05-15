// Outbound adapter — Persistence(JPA), OrderBook query, Redis, PG, Messaging(Kafka + Outbox), Storage(S3)
plugins {
    `java-library`
}

dependencies {
    implementation(project(":market-application"))
    compileOnly("org.springframework.modulith:spring-modulith-api")

    // Persistence (write — JPA)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // Redis support — idempotency key store, cache infrastructure
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson-spring-boot-starter:3.34.1")

    // 2단계 캐시
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // PG client (외부 결제)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-web")  // RestClient

    // Messaging (Outbox + Kafka)
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // S3 (검수 사진 — LocalStack 호환)
    implementation(platform("software.amazon.awssdk:bom:2.29.43"))
    implementation("software.amazon.awssdk:s3")

    // Tracing
    implementation("io.micrometer:micrometer-tracing")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}
