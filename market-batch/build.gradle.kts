// Spring Batch — 만료 ASK/BID 정리, 일일 정산, 검수 SLA 모니터링
plugins {
    `java-library`
}

dependencies {
    implementation(project(":market-application"))
    implementation(project(":market-adapter-out"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
}
