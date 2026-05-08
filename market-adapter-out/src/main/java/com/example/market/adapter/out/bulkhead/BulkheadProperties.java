package com.example.market.adapter.out.bulkhead;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 외부 호출별 ThreadPoolBulkhead 설정. 인스턴스 이름 (예: "pg", "bank") 별로 코어/큐/타임아웃을
 * 분리한다.
 *
 * <p>설계 메모 — Little's law (대기열 이론) 로 코어 수 산정:</p>
 *
 * <pre>
 *   필요한 동시 처리 수 ≈ 도착률(req/s) × 평균 처리 시간(s)
 * </pre>
 *
 * <p>예) PG authorize 가 평균 200ms, 한정판 발매 시점 peak 50 req/s 라면 동시성 = 10. 여기에
 * burst 흡수용 30~50% 여유를 더해 코어 = 12~16 정도. queue capacity 는 servlet thread 가 다
 * 점유되지 않게 짧게 (코어의 1~2배). 큐 초과 → BulkheadFullException → 컨트롤러가 503 +
 * Retry-After 로 즉시 반려.</p>
 */
@ConfigurationProperties(prefix = "market.bulkhead")
public class BulkheadProperties {

    /** 인스턴스 이름 → 설정. 키 예: "pg", "bank". */
    private final Map<String, Instance> instances = new LinkedHashMap<>();

    public Map<String, Instance> getInstances() {
        return instances;
    }

    /** 한 외부 호출의 격리 풀 설정. */
    public static class Instance {
        /** 풀 코어 스레드 수 (= 동시 처리 가능 호출 수). */
        private int coreSize = 10;

        /** 풀 최대 스레드 수 — 사실상 코어와 같게 두는 편. spike 가 흔치 않다면 코어 ≤ max. */
        private int maxPoolSize = 10;

        /** 큐 길이. 풀 포화 후 추가 호출이 잠시 줄을 설 수 있게 — 길수록 응답 대기 늘어남. */
        private int queueCapacity = 20;

        /** 호출당 최대 대기 — 이 시간 안에 코어 가 못 잡히면 BulkheadFullException. */
        private Duration awaitTimeout = Duration.ofMillis(500);

        /** 큐가 가득 찼을 때 클라이언트에 알려줄 Retry-After 초. */
        private long retryAfterSeconds = 1;

        // ── lombok 미사용 (외부 노출 객체) — 명시 getter/setter ──
        public int getCoreSize() { return coreSize; }
        public void setCoreSize(int v) { this.coreSize = v; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int v) { this.maxPoolSize = v; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
        public Duration getAwaitTimeout() { return awaitTimeout; }
        public void setAwaitTimeout(Duration v) { this.awaitTimeout = v; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
        public void setRetryAfterSeconds(long v) { this.retryAfterSeconds = v; }
    }
}
