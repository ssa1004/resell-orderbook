// Use Cases + Outbound Ports. Spring stereotype + transaction 만 허용.
plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    api(project(":market-domain"))
    api("org.springframework:spring-context")
    api("org.springframework:spring-tx")
    api("org.slf4j:slf4j-api")
    compileOnly("org.springframework.modulith:spring-modulith-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
}

kotlin {
    compilerOptions {
        // -Xjvm-default=all: Kotlin interface default 메서드를 JVM default 로 컴파일.
        // EventPublisher.publishAll 같은 default 메서드를 Java 구현체가 따로 override 하지 않아도
        // 그대로 상속 가능.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjvm-default=all")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
