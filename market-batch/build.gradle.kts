// Spring Batch — 만료 ASK/BID 정리, 일일 정산, 검수 SLA 모니터링
plugins {
    `java-library`
}

dependencies {
    implementation(project(":market-application"))
    implementation(project(":market-adapter-out"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // ShedLock — multi-instance 환경에서 @Scheduled 가 한 인스턴스에서만 실행되도록
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.13.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.13.0")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
}
