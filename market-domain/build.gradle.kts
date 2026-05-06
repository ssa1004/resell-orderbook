// 순수 도메인. Spring 런타임 의존성 0. JPA 어노테이션도 0. (헥사고날 핵심)
plugins {
    `java-library`
}

dependencies {
    api("jakarta.validation:jakarta.validation-api")
    compileOnly("org.springframework.modulith:spring-modulith-api")
    compileOnly("org.springframework:spring-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
}
