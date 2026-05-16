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
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
