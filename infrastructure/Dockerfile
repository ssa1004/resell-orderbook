# Multi-stage build — JDK 21 으로 build, JRE 21 Alpine 으로 run.
# Layered jar 로 layer cache 최적화.

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY market-domain market-domain
COPY market-application market-application
COPY market-adapter-in market-adapter-in
COPY market-adapter-out market-adapter-out
COPY market-batch market-batch
COPY market-bootstrap market-bootstrap
RUN ./gradlew :market-bootstrap:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# non-root user (k8s PSS restricted 호환)
RUN addgroup -g 10001 app && adduser -u 10001 -G app -s /bin/sh -D app
USER 10001:10001

COPY --from=builder /build/market-bootstrap/build/libs/*-boot.jar app.jar

EXPOSE 8080

# Java 21 Virtual Threads — Tomcat 자동 적용
ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational -XshowSettings:vm"

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
